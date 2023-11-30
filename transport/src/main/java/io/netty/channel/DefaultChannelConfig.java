/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.internal.ObjectUtil;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.channel.ChannelOption.ALLOCATOR;
import static io.netty.channel.ChannelOption.AUTO_CLOSE;
import static io.netty.channel.ChannelOption.AUTO_READ;
import static io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS;
import static io.netty.channel.ChannelOption.MAX_MESSAGES_PER_READ;
import static io.netty.channel.ChannelOption.MAX_MESSAGES_PER_WRITE;
import static io.netty.channel.ChannelOption.MESSAGE_SIZE_ESTIMATOR;
import static io.netty.channel.ChannelOption.RCVBUF_ALLOCATOR;
import static io.netty.channel.ChannelOption.SINGLE_EVENTEXECUTOR_PER_GROUP;
import static io.netty.channel.ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK;
import static io.netty.channel.ChannelOption.WRITE_BUFFER_LOW_WATER_MARK;
import static io.netty.channel.ChannelOption.WRITE_BUFFER_WATER_MARK;
import static io.netty.channel.ChannelOption.WRITE_SPIN_COUNT;
import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static io.netty.util.internal.ObjectUtil.checkPositive;
import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

/**
 * The default {@link ChannelConfig} implementation.
 */
public class DefaultChannelConfig implements ChannelConfig {
    private static final MessageSizeEstimator DEFAULT_MSG_SIZE_ESTIMATOR = DefaultMessageSizeEstimator.DEFAULT;

    private static final int DEFAULT_CONNECT_TIMEOUT = 30000;

    private static final AtomicIntegerFieldUpdater<DefaultChannelConfig> AUTOREAD_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(DefaultChannelConfig.class, "autoRead");
    private static final AtomicReferenceFieldUpdater<DefaultChannelConfig, WriteBufferWaterMark> WATERMARK_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(
                    DefaultChannelConfig.class, WriteBufferWaterMark.class, "writeBufferWaterMark");

    protected final Channel channel;

    private volatile ByteBufAllocator allocator = ByteBufAllocator.DEFAULT;
    private volatile RecvByteBufAllocator rcvBufAllocator;
    private volatile MessageSizeEstimator msgSizeEstimator = DEFAULT_MSG_SIZE_ESTIMATOR;

    private volatile int connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT;
    private volatile int writeSpinCount = 16;
    private volatile int maxMessagesPerWrite = Integer.MAX_VALUE;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int autoRead = 1;
    private volatile boolean autoClose = true;
    private volatile WriteBufferWaterMark writeBufferWaterMark = WriteBufferWaterMark.DEFAULT;
    private volatile boolean pinEventExecutor = true;

    public DefaultChannelConfig(Channel channel) {
        this(channel, new AdaptiveRecvByteBufAllocator());
    }

    protected DefaultChannelConfig(Channel channel, RecvByteBufAllocator allocator) {
        setRecvByteBufAllocator(allocator, channel.metadata());
        this.channel = channel;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Map<ChannelOption<?>, Object> getOptions() {
        return getOptions(
                null,
                CONNECT_TIMEOUT_MILLIS, MAX_MESSAGES_PER_READ, WRITE_SPIN_COUNT,
                ALLOCATOR, AUTO_READ, AUTO_CLOSE, RCVBUF_ALLOCATOR, WRITE_BUFFER_HIGH_WATER_MARK,
                WRITE_BUFFER_LOW_WATER_MARK, WRITE_BUFFER_WATER_MARK, MESSAGE_SIZE_ESTIMATOR,
                SINGLE_EVENTEXECUTOR_PER_GROUP, MAX_MESSAGES_PER_WRITE);
    }

    protected Map<ChannelOption<?>, Object> getOptions(
            Map<ChannelOption<?>, Object> result, ChannelOption<?>... options) {
        if (result == null) {
            result = new IdentityHashMap<ChannelOption<?>, Object>();
        }
        for (ChannelOption<?> o: options) {
            result.put(o, getOption(o));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean setOptions(Map<ChannelOption<?>, ?> options) {
        ObjectUtil.checkNotNull(options, "options");

        boolean setAllOptions = true;
        for (Entry<ChannelOption<?>, ?> e: options.entrySet()) {
            if (!setOption((ChannelOption<Object>) e.getKey(), e.getValue())) {
                setAllOptions = false;
            }
        }

        return setAllOptions;
    }

    @Override
    @SuppressWarnings({ "unchecked", "deprecation" })
    public <T> T getOption(ChannelOption<T> option) {
        ObjectUtil.checkNotNull(option, "option");

        if (option == CONNECT_TIMEOUT_MILLIS) {
            return (T) Integer.valueOf(getConnectTimeoutMillis());
        }
        if (option == MAX_MESSAGES_PER_READ) {
            return (T) Integer.valueOf(getMaxMessagesPerRead());
        }
        if (option == WRITE_SPIN_COUNT) {
            return (T) Integer.valueOf(getWriteSpinCount());
        }
        if (option == ALLOCATOR) {
            return (T) getAllocator();
        }
        if (option == RCVBUF_ALLOCATOR) {
            return (T) getRecvByteBufAllocator();
        }
        if (option == AUTO_READ) {
            return (T) Boolean.valueOf(isAutoRead());
        }
        if (option == AUTO_CLOSE) {
            return (T) Boolean.valueOf(isAutoClose());
        }
        if (option == WRITE_BUFFER_HIGH_WATER_MARK) {
            return (T) Integer.valueOf(getWriteBufferHighWaterMark());
        }
        if (option == WRITE_BUFFER_LOW_WATER_MARK) {
            return (T) Integer.valueOf(getWriteBufferLowWaterMark());
        }
        if (option == WRITE_BUFFER_WATER_MARK) {
            return (T) getWriteBufferWaterMark();
        }
        if (option == MESSAGE_SIZE_ESTIMATOR) {
            return (T) getMessageSizeEstimator();
        }
        if (option == SINGLE_EVENTEXECUTOR_PER_GROUP) {
            return (T) Boolean.valueOf(getPinEventExecutorPerGroup());
        }
        if (option == MAX_MESSAGES_PER_WRITE) {
            return (T) Integer.valueOf(getMaxMessagesPerWrite());
        }
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        validate(option, value);

        if (option == CONNECT_TIMEOUT_MILLIS) {
            setConnectTimeoutMillis((Integer) value);
        } else if (option == MAX_MESSAGES_PER_READ) {
            setMaxMessagesPerRead((Integer) value);
        } else if (option == WRITE_SPIN_COUNT) {
            setWriteSpinCount((Integer) value);
        } else if (option == ALLOCATOR) {
            setAllocator((ByteBufAllocator) value);
        } else if (option == RCVBUF_ALLOCATOR) {
            setRecvByteBufAllocator((RecvByteBufAllocator) value);
        } else if (option == AUTO_READ) {
            setAutoRead((Boolean) value);
        } else if (option == AUTO_CLOSE) {
            setAutoClose((Boolean) value);
        } else if (option == WRITE_BUFFER_HIGH_WATER_MARK) {
            setWriteBufferHighWaterMark((Integer) value);
        } else if (option == WRITE_BUFFER_LOW_WATER_MARK) {
            setWriteBufferLowWaterMark((Integer) value);
        } else if (option == WRITE_BUFFER_WATER_MARK) {
            setWriteBufferWaterMark((WriteBufferWaterMark) value);
        } else if (option == MESSAGE_SIZE_ESTIMATOR) {
            setMessageSizeEstimator((MessageSizeEstimator) value);
        } else if (option == SINGLE_EVENTEXECUTOR_PER_GROUP) {
            setPinEventExecutorPerGroup((Boolean) value);
        } else if (option == MAX_MESSAGES_PER_WRITE) {
            setMaxMessagesPerWrite((Integer) value);
        } else {
            return false;
        }

        return true;
    }

    protected <T> void validate(ChannelOption<T> option, T value) {
        ObjectUtil.checkNotNull(option, "option").validate(value);
    }

    @Override
    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    @Override
    public ChannelConfig setConnectTimeoutMillis(int connectTimeoutMillis) {
        checkPositiveOrZero(connectTimeoutMillis, "connectTimeoutMillis");
        this.connectTimeoutMillis = connectTimeoutMillis;
        return this;
    }

    /**
     * {@inheritDoc}
     * <p>
     * @throws IllegalStateException if {@link #getRecvByteBufAllocator()} does not return an object of type
     * {@link MaxMessagesRecvByteBufAllocator}.
     */
    @Override
    @Deprecated
    public int getMaxMessagesPerRead() {
        try {
            /**
             *
             * Step2：创建接受缓存区内存分配器，这里有两个关键点：
             * maxMessagesPerRead
             * 每一个通道在一次读事件处理过程中最多可以调用底层Socket进行读取的次数，默认为16
             * 次， 这里的设计哲学是避免一个通道需要读取太多的数据，从而影响其他通道的数据读，因
             * 为在一个事件选择器中多个通道的读事件是串行执行的 。
             *
             * 因为：在Netty的EventLoop线程模型中
             * 业务线程调用 Channel 对象的 write 方法并不会立即写入网络，只是
             * 将数据放入一个待写入队列(缓存区)，然后IO线程每次执行事件选择后，
             * 会从待写入缓存区中获取写入任务，将数据真正写入到网络中，
             * 数据到达网卡之前会经过一系列的 Channel Handler(Netty事件传播机制)，
             * 最终写入网卡。
             * EventLoop中有一个Selector管理多个Client SocketChannel,run方法中一次取出多个就绪的Channel，
             * 然后依次处理每一个channel
             * 每一IO线程在执行上述操作时是串行执行的，即注册在一个
             * Selector(事件选择器)中的所有通道，同一时间只有一个通道的事件被处理。
             *
             * IO 线程在处理完所有就绪事件后，还会从任务队列(Task Queue)获取任务，
             * 例如上文中提到的业务线程在执行完业务后需要将返回结果写入网络，
             * Netty 中所有的网络读写操作只能在IO线程中真正获得运行，故业务线
             * 程需要将带写入的响应结果封装成 Task，放入到 IO 线程任务队列中
             *
             *
             * 基于这样的设计，如果一个通道在一次读事件中读取了太多的数据，那么就会导致其他通道的读事件得不到及时处理，
             */
            MaxMessagesRecvByteBufAllocator allocator = getRecvByteBufAllocator();
            return allocator.maxMessagesPerRead();
        } catch (ClassCastException e) {
            throw new IllegalStateException("getRecvByteBufAllocator() must return an object of type " +
                    "MaxMessagesRecvByteBufAllocator", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * @throws IllegalStateException if {@link #getRecvByteBufAllocator()} does not return an object of type
     * {@link MaxMessagesRecvByteBufAllocator}.
     */
    @Override
    @Deprecated
    public ChannelConfig setMaxMessagesPerRead(int maxMessagesPerRead) {
        try {
            MaxMessagesRecvByteBufAllocator allocator = getRecvByteBufAllocator();
            allocator.maxMessagesPerRead(maxMessagesPerRead);
            return this;
        } catch (ClassCastException e) {
            throw new IllegalStateException("getRecvByteBufAllocator() must return an object of type " +
                    "MaxMessagesRecvByteBufAllocator", e);
        }
    }

    /**
     * Get the maximum number of message to write per eventloop run. Once this limit is
     * reached we will continue to process other events before trying to write the remaining messages.
     */
    public int getMaxMessagesPerWrite() {
        return maxMessagesPerWrite;
    }

     /**
     * Set the maximum number of message to write per eventloop run. Once this limit is
     * reached we will continue to process other events before trying to write the remaining messages.
     */
    public ChannelConfig setMaxMessagesPerWrite(int maxMessagesPerWrite) {
        this.maxMessagesPerWrite = ObjectUtil.checkPositive(maxMessagesPerWrite, "maxMessagesPerWrite");
        return this;
    }

    @Override
    public int getWriteSpinCount() {
        return writeSpinCount;
    }

    @Override
    public ChannelConfig setWriteSpinCount(int writeSpinCount) {
        checkPositive(writeSpinCount, "writeSpinCount");
        // Integer.MAX_VALUE is used as a special value in the channel implementations to indicate the channel cannot
        // accept any more data, and results in the writeOp being set on the selector (or execute a runnable which tries
        // to flush later because the writeSpinCount quantum has been exhausted). This strategy prevents additional
        // conditional logic in the channel implementations, and shouldn't be noticeable in practice.
        if (writeSpinCount == Integer.MAX_VALUE) {
            --writeSpinCount;
        }
        this.writeSpinCount = writeSpinCount;
        return this;
    }

    @Override
    public ByteBufAllocator getAllocator() {
        return allocator;
    }

    @Override
    public ChannelConfig setAllocator(ByteBufAllocator allocator) {
        this.allocator = ObjectUtil.checkNotNull(allocator, "allocator");
        return this;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends RecvByteBufAllocator> T getRecvByteBufAllocator() {
        return (T) rcvBufAllocator;
    }

    @Override
    public ChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator allocator) {
        rcvBufAllocator = checkNotNull(allocator, "allocator");
        return this;
    }

    /**
     * Set the {@link RecvByteBufAllocator} which is used for the channel to allocate receive buffers.
     * @param allocator the allocator to set.
     * @param metadata Used to set the {@link ChannelMetadata#defaultMaxMessagesPerRead()} if {@code allocator}
     * is of type {@link MaxMessagesRecvByteBufAllocator}.
     */
    private void setRecvByteBufAllocator(RecvByteBufAllocator allocator, ChannelMetadata metadata) {
        checkNotNull(allocator, "allocator");
        checkNotNull(metadata, "metadata");
        if (allocator instanceof MaxMessagesRecvByteBufAllocator) {
            ((MaxMessagesRecvByteBufAllocator) allocator).maxMessagesPerRead(metadata.defaultMaxMessagesPerRead());
        }
        setRecvByteBufAllocator(allocator);
    }

    @Override
    public boolean isAutoRead() {
        return autoRead == 1;
    }

    @Override
    public ChannelConfig setAutoRead(boolean autoRead) {
        boolean oldAutoRead = AUTOREAD_UPDATER.getAndSet(this, autoRead ? 1 : 0) == 1;
        if (autoRead && !oldAutoRead) {
            channel.read();
        } else if (!autoRead && oldAutoRead) {
            autoReadCleared();
        }
        return this;
    }

    /**
     * Is called once {@link #setAutoRead(boolean)} is called with {@code false} and {@link #isAutoRead()} was
     * {@code true} before.
     */
    protected void autoReadCleared() { }

    @Override
    public boolean isAutoClose() {
        return autoClose;
    }

    @Override
    public ChannelConfig setAutoClose(boolean autoClose) {
        this.autoClose = autoClose;
        return this;
    }

    @Override
    public int getWriteBufferHighWaterMark() {
        return writeBufferWaterMark.high();
    }

    @Override
    public ChannelConfig setWriteBufferHighWaterMark(int writeBufferHighWaterMark) {
        checkPositiveOrZero(writeBufferHighWaterMark, "writeBufferHighWaterMark");
        for (;;) {
            WriteBufferWaterMark waterMark = writeBufferWaterMark;
            if (writeBufferHighWaterMark < waterMark.low()) {
                throw new IllegalArgumentException(
                        "writeBufferHighWaterMark cannot be less than " +
                                "writeBufferLowWaterMark (" + waterMark.low() + "): " +
                                writeBufferHighWaterMark);
            }
            if (WATERMARK_UPDATER.compareAndSet(this, waterMark,
                    new WriteBufferWaterMark(waterMark.low(), writeBufferHighWaterMark, false))) {
                return this;
            }
        }
    }

    @Override
    public int getWriteBufferLowWaterMark() {
        return writeBufferWaterMark.low();
    }

    @Override
    public ChannelConfig setWriteBufferLowWaterMark(int writeBufferLowWaterMark) {
        checkPositiveOrZero(writeBufferLowWaterMark, "writeBufferLowWaterMark");
        for (;;) {
            WriteBufferWaterMark waterMark = writeBufferWaterMark;
            if (writeBufferLowWaterMark > waterMark.high()) {
                throw new IllegalArgumentException(
                        "writeBufferLowWaterMark cannot be greater than " +
                                "writeBufferHighWaterMark (" + waterMark.high() + "): " +
                                writeBufferLowWaterMark);
            }
            if (WATERMARK_UPDATER.compareAndSet(this, waterMark,
                    new WriteBufferWaterMark(writeBufferLowWaterMark, waterMark.high(), false))) {
                return this;
            }
        }
    }

    @Override
    public ChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark writeBufferWaterMark) {
        this.writeBufferWaterMark = checkNotNull(writeBufferWaterMark, "writeBufferWaterMark");
        return this;
    }

    @Override
    public WriteBufferWaterMark getWriteBufferWaterMark() {
        return writeBufferWaterMark;
    }

    @Override
    public MessageSizeEstimator getMessageSizeEstimator() {
        return msgSizeEstimator;
    }

    @Override
    public ChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator) {
        this.msgSizeEstimator = ObjectUtil.checkNotNull(estimator, "estimator");
        return this;
    }

    private ChannelConfig setPinEventExecutorPerGroup(boolean pinEventExecutor) {
        this.pinEventExecutor = pinEventExecutor;
        return this;
    }

    private boolean getPinEventExecutorPerGroup() {
        return pinEventExecutor;
    }

}
