/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.remoting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link OutputStream} that sends bits to an exported
 * {@link OutputStream} on a remote machine.
 */
final class ProxyOutputStream extends OutputStream {
    private Channel channel;
    private int oid;

    private PipeWindow window;

    /**
     * If bytes are written to this stream before it's connected
     * to a remote object, bytes will be stored in this buffer.
     */
    private ByteArrayOutputStream tmp;

    /**
     * Set to true if the stream is closed.
     */
    private boolean closed;

    /**
     * Creates unconnected {@link ProxyOutputStream}.
     * The returned stream accepts data right away, and
     * when it's {@link #connect(Channel,int) connected} later,
     * the data will be sent at once to the remote stream.
     */
    public ProxyOutputStream() {
    }

    /**
     * Creates an already connected {@link ProxyOutputStream}.
     *
     * @param oid
     *      The object id of the exported {@link OutputStream}.
     */
    public ProxyOutputStream(Channel channel, int oid) throws IOException {
        connect(channel,oid);
    }

    /**
     * Connects this stream to the specified remote object.
     */
    synchronized void connect(Channel channel, int oid) throws IOException {
        if(this.channel!=null)
            throw new IllegalStateException("Cannot connect twice");
        if(oid==0)
            throw new IllegalArgumentException("oid=0");
        this.channel = channel;
        this.oid = oid;

        window =  channel.getPipeWindow(oid);

        // if we already have bytes to write, do so now.
        if(tmp!=null) {
            byte[] b = tmp.toByteArray();
            tmp = null;
            _write(b,0,b.length);
        }
        if(closed)  // already marked closed?
            doClose();
    }

    public void write(int b) throws IOException {
        write(new byte[]{(byte)b},0,1);
    }

    public void write(byte b[], int off, int len) throws IOException {
        if(closed)
            throw new IOException("stream is already closed");
        _write(b, off, len);
    }

    /**
     * {@link #write(byte[])} without the close check.
     */
    private synchronized void _write(byte[] b, int off, int len) throws IOException {
        if(channel==null) {
            if(tmp==null)
                tmp = new ByteArrayOutputStream();
            tmp.write(b,off,len);
        } else {
            final int max = window.max();

            while (len>0) {
                int sendable;
                try {
                    /*
                        To avoid fragmentation of the pipe window, at least demand that 10% of the pipe window
                        be reclaimed.

                        Imagine a large latency network where we are always low on the window size,
                        and we are continuously sending data of irregular size. In such a circumstance,
                        a fragmentation will happen. We start sending out a small Chunk at a time (say 4 bytes),
                        and when its Ack comes back, it gets immediately consumed by another out-bound Chunk of 4 bytes.

                        Clearly, it's better to wait a bit until we have a sizable pipe window, then send out
                        a bigger Chunk, since Chunks have static overheads. This code does just that.

                        (Except when what we are trying to send as a whole is smaller than the current available
                        window size, in which case there's no point in waiting.)
                     */
                    sendable = Math.min(window.get(Math.min(max/10,len)),len);
                    /*
                        Imagine if we have a lot of data to send and the pipe window is fully available.
                        If we create one Chunk that fully uses the window size, we need to wait for the
                        whole Chunk to get to the other side, then the Ack to come back to this side,
                        before we can send a next Chunk. While the Ack is traveling back to us, we have
                        to sit idle. This fails to utilize available bandwidth.

                        A better strategy is to create a smaller Chunk, say half the window size.
                        This allows the other side to send back the ack while we are sending the second
                        Chunk. In a network with a non-trivial latency, this allows Chunk and Ack
                        to overlap, and that improves the utilization.

                        It's not clear what the best size of the chunk to send (there's a certain
                        overhead in our Command structure, around 100-200 bytes), so I'm just starting
                        with 2. Further analysis would be needed to determine the best value.
                     */
                    sendable = Math.min(sendable, max /2);
                } catch (InterruptedException e) {
                    throw (IOException)new InterruptedIOException().initCause(e);
                }

                channel.send(new Chunk(channel.newIoId(),oid,b,off,sendable));
                window.decrease(sendable);
                off+=sendable;
                len-=sendable;
            }
        }
    }

    public synchronized void flush() throws IOException {
        if(channel!=null)
            channel.send(new Flush(channel.newIoId(),oid));
    }

    public synchronized void close() throws IOException {
        closed = true;
        if(channel!=null)
            doClose();
    }

    private void doClose() throws IOException {
        channel.send(new EOF(channel.newIoId(),oid));
        channel = null;
        oid = -1;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        // if we haven't done so, release the exported object on the remote side.
        if(channel!=null) {
            channel.send(new Unexport(channel.newIoId(),oid));
            channel = null;
            oid = -1;
        }
    }

    /**
     * I/O operations in remoting gets executed by a separate pipe thread asynchronously.
     * So if a closure performs some I/O (such as writing to the RemoteOutputStream) then returns,
     * it is possible that the calling thread unblocks before the I/O actually completes.
     * <p>
     * This race condition creates a truncation problem like JENKINS-9189 or JENKINS-7871.
     * The initial fix for this was to introduce {@link Channel#syncLocalIO()}, but given the
     * recurrence in JENKINS-9189, I concluded that it's too error prone to expect the user of the
     * remoting to make such a call in the right place.
     * <p>
     * So the goal of this code is to automatically ensure the proper ordering of the return from
     * the {@link Request#call(Channel)} and the I/O operations done during the call. We do this
     * by attributing I/O call to a {@link Request}, then keeping track of the last I/O operation
     * performed.
     *
     * @deprecated as of 2.16
     *      {@link PipeWriter} does this job better, but kept for backward compatibility to communicate
     *      with earlier version of remoting without losing the original fix to JENKINS-9189 completely.
     */
    private static void markForIoSync(Channel channel, int requestId, java.util.concurrent.Future<?> ioOp) {
        Request<?,?> call = channel.pendingCalls.get(requestId);
        // call==null if:
        //  1) the remote peer uses old version that doesn't set the requestId field
        //  2) a bug in the code, but in that case we are being defensive
        if (call!=null)
            call.lastIo = ioOp;
    }

    /**
     * {@link Command} for sending bytes.
     */
    private static final class Chunk extends Command {
        private final int oid;
        private final int ioId;
        private final int requestId = Request.getCurrentRequestId();
        private final byte[] buf;

        public Chunk(int ioId, int oid, byte[] buf, int start, int len) {
            // to improve the performance when a channel is used purely as a pipe,
            // don't record the stack trace. On FilePath.writeToTar case, the stack trace and the OOS header
            // takes up about 1.5K.
            super(false);
            this.ioId = ioId;
            this.oid = oid;
            if (start==0 && len==buf.length)
                this.buf = buf;
            else {
                this.buf = new byte[len];
                System.arraycopy(buf,start,this.buf,0,len);
            }
        }

        protected void execute(final Channel channel) {
            final OutputStream os = (OutputStream) channel.getExportedObject(oid);
            markForIoSync(channel,requestId,channel.pipeWriter.submit(ioId,new Runnable() {
                public void run() {
                    try {
                        os.write(buf);
                    } catch (IOException e) {
                        try {
                            channel.send(new NotifyDeadWriter(channel,e,oid));
                        } catch (ChannelClosedException x) {
                            // the other direction can be already closed if the connection
                            // shut down is initiated from this side. In that case, remain silent.
                        } catch (IOException x) {
                            // ignore errors
                            LOGGER.log(Level.WARNING, "Failed to notify the sender that the write end is dead",x);
                            LOGGER.log(Level.WARNING, "... the failed write was:",e);
                        }
                    } finally {
                        if (channel.remoteCapability.supportsPipeThrottling()) {
                            try {
                                channel.send(new Ack(oid,buf.length));
                            } catch (ChannelClosedException x) {
                                // the other direction can be already closed if the connection
                                // shut down is initiated from this side. In that case, remain silent.
                            } catch (IOException e) {
                                // ignore errors
                                LOGGER.log(Level.WARNING, "Failed to ack the stream",e);
                            }
                        }
                    }
                }
            }));
        }

        public String toString() {
            return "Pipe.Chunk("+oid+","+buf.length+")";
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * {@link Command} for flushing.
     */
    private static final class Flush extends Command {
        private final int oid;
        private final int requestId = Request.getCurrentRequestId();
        private final int ioId;

        public Flush(int ioId, int oid) {
            super(false);
            this.ioId = ioId;
            this.oid = oid;
        }

        protected void execute(Channel channel) {
            final OutputStream os = (OutputStream) channel.getExportedObject(oid);
            if (os == null) {
                return;
            }
            markForIoSync(channel,requestId,channel.pipeWriter.submit(ioId,new Runnable() {
                public void run() {
                    try {
                        os.flush();
                    } catch (IOException e) {
                        // ignore errors
                    }
                }
            }));
        }

        public String toString() {
            return "Pipe.Flush("+oid+")";
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * {@link Command} for releasing an export table.
     *
     * <p>
     * Unlike {@link EOF}, this just unexports but not closes the stream.
     */
    private static class Unexport extends Command {
        private final int oid;
        private final int ioId;

        public Unexport(int ioId, int oid) {
            this.ioId = ioId;
            this.oid = oid;
        }

        protected void execute(final Channel channel) {
            channel.pipeWriter.submit(ioId,new Runnable() {
                public void run() {
                    channel.unexport(oid);
                }
            });
        }

        public String toString() {
            return "Pipe.Unexport("+oid+")";
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * {@link Command} for sending EOF.
     */
    private static final class EOF extends Command {
        private final int oid;
        private final int requestId = Request.getCurrentRequestId();
        private final int ioId;

        public EOF(int ioId, int oid) {
            this.ioId = ioId;
            this.oid = oid;
        }


        protected void execute(final Channel channel) {
            final OutputStream os = (OutputStream) channel.getExportedObject(oid);
            if (os == null) {
                return;
            }
            markForIoSync(channel,requestId,channel.pipeWriter.submit(ioId,new Runnable() {
                public void run() {
                    channel.unexport(oid);
                    try {
                        os.close();
                    } catch (IOException e) {
                        // ignore errors
                    }
                }
            }));
        }

        public String toString() {
            return "Pipe.EOF("+oid+")";
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * {@link Command} to notify the sender that it can send some more data.
     */
    private static class Ack extends Command {
        /**
         * The oid of the {@link OutputStream} on the receiver side of the data.
         */
        private final int oid;
        /**
         * The number of bytes that were freed up.
         */
        private final int size;

        private Ack(int oid, int size) {
            super(false); // performance optimization
            this.oid = oid;
            this.size = size;
        }

        protected void execute(Channel channel) {
            PipeWindow w = channel.getPipeWindow(oid);
            w.increase(size);
        }

        public String toString() {
            return "Pipe.Ack("+oid+','+size+")";
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * {@link Command} to notify the sender that the receiver is dead.
     */
    private static final class NotifyDeadWriter extends Command {
        private final int oid;

        private NotifyDeadWriter(Channel channel,Throwable cause, int oid) {
            super(channel,cause);
            this.oid = oid;
        }

        @Override
        protected void execute(Channel channel) {
            PipeWindow w = channel.getPipeWindow(oid);
            w.dead(createdAt.getCause());
        }

        public String toString() {
            return "Pipe.Dead("+oid+")";
        }

        private static final long serialVersionUID = 1L;
    }

    private static final Logger LOGGER = Logger.getLogger(ProxyOutputStream.class.getName());
}
