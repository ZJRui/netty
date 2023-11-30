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
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
@SuppressWarnings("all")
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    /**
     * AbstractNioChannel拥有NIO的Channel，具备NIO的注册、连接等
     * 功能。但I/O的读/写交给了其子类，Netty对I/O的读/写分为POJO对象
     * 与ByteBuf和FileRegion，因此在AbstractNioChannel的基础上继续抽
     * 象 了 一 层 ， 分 为 AbstractNioMessageChannel 与
     * AbstractNioByteChannel
     *
     * AbstractNioByteChannel: 发 送 和 读 取 的 对 象 是 ByteBuf 与FileRegion类型。
     *
     */
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    /**
     *
     * 属性flushTask为task任务，主要负责刷新发送缓存链表中的数
     * 据 ， 由 于 write 的 数 据 没 有 直 接 写 在 Socket 中 ， 而 是 写 在 了
     * ChannelOutboundBuffer缓存中，所以当调用flush()方法时，会把数
     * 据写入Socket中并向网络中发送。因此当缓存中的数据未发送完成
     * 时，需要将此任务添加到EventLoop线程中，等待EventLoop线程的再
     * 次发送。
     *
     */
    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };
    private boolean inputClosedSeenErrorOnRead;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        super(parent, ch, SelectionKey.OP_READ);
    }

    /**
     * Shutdown the input side of the channel.
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    protected class NioByteUnsafe extends AbstractNioUnsafe {

        private void closeOnRead(ChannelPipeline pipeline) {
            if (!isInputShutdown0()) {
                if (isAllowHalfClosure(config())) {
                    shutdownInput();
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            } else {
                inputClosedSeenErrorOnRead = true;
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) {
                if (byteBuf.isReadable()) {
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    byteBuf.release();
                }
            }
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);

            // If oom will close the read event, release connection.
            // See https://github.com/netty/netty/issues/10434
            if (close || cause instanceof OutOfMemoryError || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        @Override
        public final void read() {
            /**
             * NioByteUnsafe的read()方法的实现思路大概分为以下3步。
             * (1) ）获取Channel的配置对象、内存分配器ByteBufAllocator，
             * 并计算内存分配器RecvByte BufAllocator.Handle。
             *
             *（2）进入for循环。循环体的作用：使用内存分配器获取数据容
             * 器ByteBuf，调用doReadBytes()方法将数据读取到容器中，如果本次
             * 循环没有读到数据或链路已关闭，则跳出循环。另外，当循环次数达
             * 到属性METADATA的defaultMaxMessagesPerRead次数（默认为16）时，
             * 也会跳出循环。由于TCP传输会产生粘包问题，因此每次读取都会触发
             * channelRead事件，进而调用业务逻辑处理Handler。
             *
             * （3）跳出循环后，表示本次读取已完成。调用allocHandle的
             * readComplete()方法，并记录读取记录，用于下次分配合理内存。
             *
             */
            //获取pipeline通道配置，channel管道
            final ChannelConfig config = config();
            //socketChannel 已经关闭
            if (shouldBreakReadReady(config)) {
                clearReadPending();
                return;
            }
            final ChannelPipeline pipeline = pipeline();
            /**
             * 获取内存分配器，默认为 PooledByteBufAllocator
             * 在应用Netty时，通过默认设
             * 置PooledByteBufAllocator执行ByteBuf的分配。当用NioByteUnsafe
             * 的 read() 方 法 读 取 NioSocketChannel 数 据 时 ， 需 要 调 用
             * PooledByteBufAllocator去分配内存，具体分配多少内存，由Handle
             * 的guess()方法决定，此方法只预测所需的缓冲区的大小，不进行实际
             * 的 分 配 。 PooledByteBufAllocator 从 PoolThreadLocalCache 中 获 取
             * PoolArena，最终的内存分配工作由PoolArena完成。
             */
            final ByteBufAllocator allocator = config.getAllocator();
            /**
             * RecvByteBufAllocator
             * 接受缓冲区的内存分配策略，分为分配固定大小(不够时扩容)、动态变化(根据历史分配的大
             * 小，动态条件合适的内存大小)， 这里主要的设计哲学是合理利用内存，并减少扩容，提高
             * 内存的分配效率与使用效率 。
             */
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            /**
             * 清空上一次读取的字节数，每次读取时均重新计算 字节buf分配器，并计算字节buf分配器handler
             */
            allocHandle.reset(config);

            ByteBuf byteBuf = null;
            boolean close = false;
            try {
                do {
                    /**
                     * 分配内存
                     * 首先分配一个ByteBuf，俗称接收缓存区，用来存放从网络中读取的内容。
                     */
                    byteBuf = allocHandle.allocate(allocator);
                    /**
                     * 读取通道接收缓冲区的数据
                     */
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    if (allocHandle.lastBytesRead() <= 0) {
                        /**
                         * 如果没有数据可读取，则释放内存
                         */
                        // nothing was read. release the buffer.
                        byteBuf.release();
                        byteBuf = null;
                        close = allocHandle.lastBytesRead() < 0;
                        if (close) {
                            /**
                             * 当读取到-1 时，表示channel通道已经关闭。 没必要继续读
                             *
                             * 即调用NIO中的SocketChannel进行读数据，其返回参数表示这次从网卡中读取到的字节
                             * 数。 如果读取到的字节少于0，则表示对端通道已关闭，己端也需要进行相应的处理，例如
                             * 关闭通道 。
                             */
                            // There is nothing left to read as we received an EOF.
                            readPending = false;
                        }
                        break;
                    }
                    /**
                     * 更新读取消息计数器
                     */
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    /**
                     * 1.通知通道处理读取到的数据，触发channel管道的fireChannelRead事件
                     *
                     * 在读数据时，AbstractNioMessageChannel数据不存在粘包问题，
                     * 因此AbstractNioMessageChannel在read()方法中先循环读取数据包，
                     * 再触发channelRead事件。
                     *
                     * NioByteUnsafe不断地调用NioSocketChannel
                     * 的doReadBytes()方法从Channel中读取数据，再把读取到的ByteBuf交
                     * 给 管 道 Pipeline ， 并 触 发 后 续 一 系 列 ChannelInboundHandler 的
                     * channelRead()方法。整个读取数据的过程涉及的Handler都是以
                     * HeadContext开头的，按顺序运行用户自定义的各个解码器和服务端业
                     * 务逻辑处理Handler。
                     *
                     * 2.读到一批数据后，会通过事件传播机制向事件链中传播channelRead事件，触发后续对该批数据的处理。
                     *
                     * question：NioByteUnsafe这里的while中每次执行应该是读取到了部分数据，这些部分数据fireChannelRead 是如何处理没有读取到完整的数据的
                     * Handlers是用户设置的编/解码器，。对于用户选择的解码器，除了MessageToMessageCodec的子类，其他解码器首
                     * 先 都 会 经 过 其 父 类 ByteToMessageDecoder 的 channelRead() 方 法 处理ByteBuf,然后在调用子类的decode()方法，
                     *
                     *
                     * 3.完成一次异步读之后，就会触发一次ChannelRead事件，这里要特
                     * 别提醒大家的是：完成一次读操作，并不意味着读到了一条完整的消
                     * 息，因为TCP底层存在组包和粘包，所以，一次读操作可能包含多条消
                     * 息，也可能是一条不完整的消息。因此不要把它跟读取的消息个数等
                     * 同起来。在没有做任何半包处理的情况下，以ChannelRead的触发次数
                     * 做计数器来进行性能分析和统计，是完全错误的。当然，如果你使用
                     * 了半包解码器或者处理了半包，就能够实现一次 ChannelRead对应一
                     * 条完整的消息。
                     *
                     * 4.连续的读操作会阻塞排在后面的任务队列中待执行的Task，以及写操作，所
                     * 以，要对连续读操作做上限控制，默认值为16次，无论TCP缓冲区有多
                     * 少码流需要读取，只要连续16次没有读完，都需要强制退出，等待下
                     * 次selector轮询周期再执行。
                     *
                     * 5. 底 层 的 SocketChannel  read() 方 法 读 取 ByteBuf ， 触 发
                     * ChannelRead事件，由I/O线程NioEventLoop调用ChannelPipeline的
                     * fireChannelRead(Object  msg) 方 法 ， 将 消 息 （ ByteBuf ） 传 输 到
                     * ChannelPipeline中；
                     */
                    pipeline.fireChannelRead(byteBuf);
                    byteBuf = null;

                    /**
                     *
                     * 这里的while 判断的依据：
                     *
                     * Step2：创建接受缓存区内存分配器，这里有两个关键点：
                     * maxMessagesPerRead：每一个通道在一次读事件处理过程中最多可以调用底层Socket进行读取的次数，默认为16
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
                     *
                     *
                     * 2.进入for循环。循环体的作用：使用内存分配器获取数据容
                     * 器ByteBuf，调用doReadBytes()方法将数据读取到容器中，如果本次
                     * 循环没有读到数据或链路已关闭，则跳出循环。另外，当循环次数达
                     * 到属性METADATA的defaultMaxMessagesPerRead次数（默认为16）时，
                     * 也会跳出循环。由于TCP传输会产生粘包问题，因此每次读取都会触发
                     * channelRead事件，进而调用业务逻辑处理Handler。
                     *
                     */
                } while (allocHandle.continueReading());

                /**
                 * 跳出循环后，表示本次读取已完成。调用allocHandle的
                 * readComplete()方法，并记录读取记录，用于下次分配合理内存。
                 */
                allocHandle.readComplete();
                // Step6：一次或多次读操作结束后，会触发一次读完成事件，向整个事件链传播。
                pipeline.fireChannelReadComplete();

                if (close) {
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                /**
                 * Step1：如果没有开启自动注册读事件，在每一次读事件处理过后会取消读事件，默认为自动注册。
                 * 温馨提示：如果通道不注册读事件，将无法从通道中读取数据，即无法处理请求或接受响应。
                 *
                 * 如果没有开启自动读事件，需要应用程序在需要的时候手动调用通道的read方法。
                 */
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    /**
                     * 若读操作完毕，且没有配置自动读，则从选择key兴趣集中移除读操作事件
                     */
                    removeReadOp();
                }
            }
        }
    }

    /**
     * Write objects to the OS.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but no
     *     data was accepted</li>
     * </ul>
     * @throws Exception if an I/O exception occurs during write.
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg == null) {
            // Directly return here so incompleteWrite(...) is not called.
            return 0;
        }
        return doWriteInternal(in, in.current());
    }

    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                /**
                 * 如果可读字节为0，则从缓存区中移除
                 */
                in.remove();
                return 0;
            }
            //实际发送数据
            final int localFlushedAmount = doWriteBytes(buf);
            if (localFlushedAmount > 0) {
                /**
                 * 更新这季节数据的发送进度
                 */
                in.progress(localFlushedAmount);
                if (!buf.isReadable()) {
                    /**
                     * 若可读字节为0，则从缓存区中移除
                     */
                    in.remove();
                }
                return 1;
            }
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            if (region.transferred() >= region.count()) {
                in.remove();
                return 0;
            }

            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            // Should not reach here.
            throw new Error();
        }
        /**
         * 当实际发送字节数为0时，返回 maxValue
         */
        return WRITE_STATUS_SNDBUF_FULL;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        /**
         * doWrite 主要是从channelOutboundBuffer缓存中获取待发送的数据，进行循环发送，发送的结果分为三种：
         * （1）发送成功 跳出循环 直接返回
         * （2）由于TCP缓存区已满，成功发送的字节数为0，跳出循环，并
         * 将写操作OP_WRITE事件添加到选择Key兴趣事件集中。
         * （3）默认当写了16次数据还未发送完时，把选择Key的OP_WRITE
         * 事件从兴趣事件集中移除，并添加一个flushTask任务，先去执行其他
         * 任务，当检测到此任务时再发送
         */

        /**
         * 写请求自循环次数，默认为16次
         */
        int writeSpinCount = config().getWriteSpinCount();
        do {
            /**
             * 获取当前channel的缓存 channeloutboundBuffer 中的当前待刷新的消息
             */
            Object msg = in.current();
            /*
            所有消息都发送成功了
             */
            if (msg == null) {
                // Wrote all messages.
                /**
                 * 清除channel选择key兴趣事件集中的op_write写操作事件
                 */
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                //直接返回，二米必要再添加写任务。
                return;
            }
            //发送数据
            writeSpinCount -= doWriteInternal(in, msg);
        } while (writeSpinCount > 0);

        /**
         * 当因缓冲区满了而发送失败时  doWriteInternal返回 Integer.maxvalue
         * 此时writeSpinCount<0 为true
         * 当发送16次还未全部发送完，单每次都写成功时 writespincount为0
         */
        incompleteWrite(writeSpinCount < 0);
    }

    @Override
    protected final Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isDirect()) {
                return msg;
            }

            return newDirectBuffer(buf);
        }

        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        if (setOpWrite) {
            /**
             * 将op_write 写操作事件添加到channel的选择key兴趣事件集合中
             */
            setOpWrite();
        } else {
            /**
             * 清除channel选择key兴趣事件集中的op_write写操作事件
             */
            // It is possible that we have set the write OP, woken up by NIO because the socket is writable, and then
            // use our write quantum. In this case we no longer want to set the write OP because the socket is still
            // writable (as far as we know). We will find out next time we attempt to write if the socket is writable
            // and set the write OP if necessary.
            clearOpWrite();

            // 将写操作任务添加到EventLoop线程上，以便后续继续发送
            // Schedule flush again later so other tasks can be picked up in the meantime
            eventLoop().execute(flushTask);
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
