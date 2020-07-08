package com.actiontech.dble.net.connection;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.backend.mysql.CharsetUtil;
import com.actiontech.dble.config.model.SystemConfig;
import com.actiontech.dble.net.IOProcessor;
import com.actiontech.dble.net.SocketWR;
import com.actiontech.dble.net.WriteOutTask;
import com.actiontech.dble.net.mysql.CharsetNames;
import com.actiontech.dble.net.service.AbstractService;
import com.actiontech.dble.net.service.AuthResultInfo;
import com.actiontech.dble.net.service.Service;
import com.actiontech.dble.util.TimeUtil;
import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.NetworkChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by szf on 2020/6/15.
 */
public abstract class AbstractConnection implements Connection {

    protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractConnection.class);

    protected final NetworkChannel channel;

    protected final SocketWR socketWR;

    protected volatile boolean isClosed = false;


    private volatile AbstractService service;
    protected IOProcessor processor;

    protected volatile String closeReason;

    //真实的net层级的信息
    protected String host;
    protected int localPort;
    protected int port;


    protected long id;

    protected volatile ByteBuffer readBuffer;

    //写出队列，由NIOSokecetWR写入写出
    protected final ConcurrentLinkedQueue<WriteOutTask> writeQueue = new ConcurrentLinkedQueue<>();

    private volatile boolean flowControlled;

    //连接设置
    protected int readBufferChunk;
    protected int maxPacketSize;
    protected volatile CharsetNames charsetName = new CharsetNames();
    protected volatile boolean isSupportCompress = false;


    //统计值先不值得多管
    protected long startupTime;
    protected long lastReadTime;
    protected long lastWriteTime;
    protected long netInBytes;
    protected long netOutBytes;

    protected long lastLargeMessageTime;


    public AbstractConnection(NetworkChannel channel, SocketWR socketWR) {
        this.channel = channel;
        this.socketWR = socketWR;
        this.startupTime = TimeUtil.currentTimeMillis();
        this.lastReadTime = startupTime;
        this.lastWriteTime = startupTime;
    }


    public void onReadData(int got) throws IOException {
        if (isClosed) {
            return;
        }

        lastReadTime = TimeUtil.currentTimeMillis();
        if (lastReadTime == lastWriteTime) {
            lastWriteTime--;
        }
        if (got < 0) {
            this.close("stream closed");
            return;
        } else if (got == 0 && !this.channel.isOpen()) {
            this.close("stream closed");
            return;
        }
        netInBytes += got;
        //LOGGER.debug("-------------------------------------------NET IN BYTES ======== " + netInBytes);
        if (netInBytes > 15000) {
            LOGGER.debug("----");
        }
        service.handle(readBuffer);
    }


    public void close(String reason) {
        if (!isClosed) {
            closeSocket();
            isClosed = true;
            if (processor != null) {
                processor.removeConnection(this);
            }

            this.cleanup();
            isSupportCompress = false;

            // ignore null information
            if (Strings.isNullOrEmpty(reason)) {
                return;
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("close connection,reason:" + reason + " ," + this);
            }
            if (reason.contains("connection,reason:java.net.ConnectException")) {
                throw new RuntimeException(reason);
            }
        } else {
            // make sure buffer recycle again, avoid buffer leak
            this.cleanup();
        }
    }

    public void close(Exception exception) {
        LOGGER.info("get Exception close ", exception);
        this.close(exception.getMessage());
    }

    private void closeSocket() {
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception e) {
                LOGGER.info("AbstractConnectionCloseError", e);
            }

            boolean closed = !channel.isOpen();
            if (!closed) {
                LOGGER.info("close socket of connnection failed " + this);
            }
        }
    }


    public void compactReadBuffer(ByteBuffer buffer, int offset) {
        if (buffer == null) {
            return;
        }
        buffer.limit(buffer.position());
        buffer.position(offset);
        this.readBuffer = buffer.compact();
    }

    public void ensureFreeSpaceOfReadBuffer(ByteBuffer buffer,
                                            int offset, final int pkgLength) {
        if (buffer.capacity() < pkgLength) {
            ByteBuffer newBuffer = processor.getBufferPool().allocate(pkgLength);
            lastLargeMessageTime = TimeUtil.currentTimeMillis();
            buffer.position(offset);
            newBuffer.put(buffer);
            readBuffer = newBuffer;
            recycle(buffer);
        } else {
            if (offset != 0) {
                // compact bytebuffer only
                compactReadBuffer(buffer, offset);
            } else {
                throw new RuntimeException(" not enough space");
            }
        }
    }

    public void readReachEnd() {
        // if cur buffer is temper none direct byte buffer and not
        // received large message in recent 30 seconds
        // then change to direct buffer for performance
        if (readBuffer != null && !readBuffer.isDirect() &&
                lastLargeMessageTime < lastReadTime - 30 * 1000L) {  // used temp heap
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("change to direct con read buffer ,cur temp buf size :" + readBuffer.capacity());
            }
            recycle(readBuffer);
            readBuffer = processor.getBufferPool().allocate(readBufferChunk);
        } else {
            if (readBuffer != null) {
                readBuffer.clear();
            }
        }
    }


    public void setSocketParams(boolean isFrontChannel) throws IOException {
        SystemConfig system = SystemConfig.getInstance();
        int soRcvBuf;
        int soSndBuf;
        int soNoDelay;
        if (isFrontChannel) {
            soRcvBuf = system.getFrontSocketSoRcvbuf();
            soSndBuf = system.getFrontSocketSoSndbuf();
            soNoDelay = system.getFrontSocketNoDelay();
        } else {
            soRcvBuf = system.getBackSocketSoRcvbuf();
            soSndBuf = system.getBackSocketSoSndbuf();
            soNoDelay = system.getBackSocketNoDelay();
        }

        channel.setOption(StandardSocketOptions.SO_RCVBUF, soRcvBuf);
        channel.setOption(StandardSocketOptions.SO_SNDBUF, soSndBuf);
        channel.setOption(StandardSocketOptions.TCP_NODELAY, soNoDelay == 1);
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);

        this.setMaxPacketSize(system.getMaxPacketSize());
        this.initCharacterSet(system.getCharset());
        this.setReadBufferChunk(soRcvBuf);
    }

    public void initCharacterSet(String name) {
        charsetName.setClient(name);
        charsetName.setResults(name);
        charsetName.setCollation(CharsetUtil.getDefaultCollation(name));
    }

    public void initCharsetIndex(int ci) {
        String name = CharsetUtil.getCharset(ci);
        if (name != null) {
            charsetName.setClient(name);
            charsetName.setResults(name);
            charsetName.setCollation(CharsetUtil.getDefaultCollation(name));
        }
    }

    public final void recycle(ByteBuffer buffer) {
        this.processor.getBufferPool().recycle(buffer);
    }

    public final long getId() {
        return id;
    }


    public AbstractService getService() {
        return service;
    }

    public abstract void setConnProperties(AuthResultInfo info);

    public ByteBuffer allocate() {
        int size = this.processor.getBufferPool().getChunkSize();
        return this.processor.getBufferPool().allocate(size);
    }

    public ByteBuffer allocate(int size) {
        return this.processor.getBufferPool().allocate(size);
    }

    public ByteBuffer writeToBuffer(byte[] src, ByteBuffer buffer) {
        int offset = 0;
        int length = src.length;
        int remaining = buffer.remaining();
        while (length > 0) {
            if (remaining >= length) {
                buffer.put(src, offset, length);
                break;
            } else {
                buffer.put(src, offset, remaining);
                writePart(buffer);
                buffer = allocate();
                offset += remaining;
                length -= remaining;
                remaining = buffer.remaining();
            }
        }
        return buffer;
    }

    public ByteBuffer checkWriteBuffer(ByteBuffer buffer, int capacity, boolean writeSocketIfFull) {
        if (capacity > buffer.remaining()) {
            if (writeSocketIfFull) {
                writePart(buffer);
                return processor.getBufferPool().allocate(capacity);
            } else { // Relocate a larger buffer
                buffer.flip();
                ByteBuffer newBuf = processor.getBufferPool().allocate(capacity + buffer.limit() + 1);
                newBuf.put(buffer);
                this.recycle(buffer);
                return newBuf;
            }
        } else {
            return buffer;
        }
    }


    public void writePart(ByteBuffer buffer) {
        write(buffer);
    }

    public final boolean registerWrite(ByteBuffer buffer) {

        // if ansyn writeDirectly finished event got lock before me ,then writing
        // flag is set false but not start a writeDirectly request
        // so we check again
        try {
            return this.socketWR.registerWrite(buffer);
        } catch (Exception e) {
            LOGGER.info("writeDirectly err:", e);
            this.close("writeDirectly err:" + e);
            return false;
        }
    }

    public void write(byte[] data) {
        service.writeDirectly(data);
    }

    public void write(ByteBuffer buffer) {
        if (isClosed) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("it will not writeDirectly because of closed " + this);
            }
            if (buffer != null) {
                recycle(buffer);
            }
            this.cleanup();
            return;
        }

        //这个地方不在connection层进行处理，而是由service层进行处理
       /* if (isSupportCompress()) {
            ByteBuffer newBuffer = CompressUtil.compressMysqlPacket(buffer, this, compressUnfinishedDataQueue);
            writeQueue.offer(newBuffer);
        } else {*/
        writeQueue.offer(new WriteOutTask(buffer, false));

        // if ansyn writeDirectly finished event got lock before me ,then writing
        // flag is set false but not start a writeDirectly request
        // so we check again
        try {
            this.socketWR.doNextWriteCheck();
        } catch (Exception e) {
            LOGGER.info("writeDirectly err:", e);
            this.close("writeDirectly err:" + e);
        }
    }

    private void write() {

    }

    public boolean isClosed() {
        return isClosed;
    }

    public ByteBuffer findReadBuffer() {
        if (readBuffer == null) {
            readBuffer = processor.getBufferPool().allocate(processor.getBufferPool().getChunkSize());
        }
        return readBuffer;
    }


    public void onConnectFailed(Throwable e) {
    }

    public synchronized void cleanup() {

        if (readBuffer != null) {
            this.recycle(readBuffer);
            this.readBuffer = null;
        }

        service.cleanup();

        /** todo : 压缩和解压的故事不应该在这网络级别进行处理
         if (!decompressUnfinishedDataQueue.isEmpty()) {
         decompressUnfinishedDataQueue.clear();
         }

         if (!compressUnfinishedDataQueue.isEmpty()) {
         compressUnfinishedDataQueue.clear();
         }**/
        WriteOutTask task;
        while ((task = writeQueue.poll()) != null) {
            recycle(task.getBuffer());
        }
    }

    public void writeStatistics(int netOutBytes) {
        this.netOutBytes += netOutBytes;
        processor.addNetOutBytes(netOutBytes);
        lastWriteTime = TimeUtil.currentTimeMillis();
    }

    public NetworkChannel getChannel() {
        return channel;
    }

    public SocketWR getSocketWR() {
        return socketWR;
    }

    public void register() throws IOException {
        this.service.register();
    }

    public ConcurrentLinkedQueue<WriteOutTask> getWriteQueue() {
        return writeQueue;
    }

    public void asyncRead() throws IOException {
        this.socketWR.asyncRead();
    }

    public void doNextWriteCheck() throws IOException {
        this.socketWR.doNextWriteCheck();
    }

    public void setProcessor(IOProcessor processor) {
        this.processor = processor;
    }

    public void setId(long id) {
        this.id = id;
    }

    /**
     * 流量控制相关方法，暂时不管，后续再进行处理
     * ----------------------------------------------------------------------------------------------------------------------
     */

    public boolean isFlowControlled() {
        return flowControlled;
    }

    public void setFlowControlled(boolean flowControlled) {
        this.flowControlled = flowControlled;
    }

    public abstract void startFlowControl(BackendConnection bcon);

    public abstract void stopFlowControl();


    public int getLocalPort() {
        return localPort;
    }

    public void setLocalPort(int localPort) {
        this.localPort = localPort;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public synchronized void setService(AbstractService service) {
        this.service = service;
    }


    public int getMaxPacketSize() {
        return maxPacketSize;
    }

    public void setMaxPacketSize(int maxPacketSize) {
        this.maxPacketSize = maxPacketSize;
    }

    public int getReadBufferChunk() {
        return readBufferChunk;
    }

    public void setReadBufferChunk(int readBufferChunk) {
        this.readBufferChunk = readBufferChunk;
    }


    public CharsetNames getCharsetName() {
        return charsetName;
    }

    public void setCharacterSet(String name) {
        charsetName.setClient(name);
        charsetName.setResults(name);
        charsetName.setCollation(DbleServer.getInstance().getSystemVariables().getDefaultValue("collation_database"));
    }

    public void setCharsetName(CharsetNames charsetName) {
        this.charsetName = charsetName;
    }


    public boolean isSupportCompress() {
        return isSupportCompress;
    }

    public void setSupportCompress(boolean supportCompress) {
        isSupportCompress = supportCompress;
    }


    public String getCloseReason() {
        return closeReason;
    }

    public IOProcessor getProcessor() {
        return processor;
    }

}