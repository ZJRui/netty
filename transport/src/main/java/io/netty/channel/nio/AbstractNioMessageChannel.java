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

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on messages.
 */
@SuppressWarnings("all")
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {
    /**
     * AbstractNioChannel拥有NIO的Channel，具备NIO的注册、连接等
     * 功能。但I/O的读/写交给了其子类，Netty对I/O的读/写分为POJO对象
     * 与ByteBuf和FileRegion，因此在AbstractNioChannel的基础上继续抽
     * 象 了 一 层 ， 分 为 AbstractNioMessageChannel 与
     * AbstractNioByteChannel
     *
     *AbstractNioByteChannel: 发 送 和 读 取 的 对 象 是 ByteBuf 与FileRegion类型。
     */
    boolean inputShutdown;

    /**
     * @see AbstractNioChannel#AbstractNioChannel(Channel, SelectableChannel, int)
     */
    protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent, ch, readInterestOp);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioMessageUnsafe();
    }

    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }
        super.doBeginRead();
    }

    protected boolean continueReading(RecvByteBufAllocator.Handle allocHandle) {
        return allocHandle.continueReading();
    }

    private final class NioMessageUnsafe extends AbstractNioUnsafe {

        private final List<Object> readBuf = new ArrayList<Object>();

        @Override
        public void read() {
            /**
             * 在读数据时，AbstractNioMessageChannel数据不存在粘包问题，
             * 因此AbstractNioMessageChannel在read()方法中先循环读取数据包，
             * 再触发channelRead事件
             *
             * 在写数据时，AbstractNioMessageChannel数据逻辑简单。它把缓
             * 存outboundBuffer中的数据包依次写入Channel中。如果Channel写满
             * 了 ， 或 循 环 写 、 默 认 写 的 次 数 为 子 类 Channel 属 性 METADATA 中 的
             * defaultMaxMessagesPerRead次数，则在Channel的SelectionKey上设
             * 置OP_WRITE事件，随后退出，其后OP_WRITE事件处理逻辑和Byte字节
             * 流写逻辑一样
             */
            assert eventLoop().inEventLoop();
            final ChannelConfig config = config();
            final ChannelPipeline pipeline = pipeline();
            /**
             *  接收对端数据时，ByteBuf的分配策略，基于历史数据动态调整初始化大小，避免太大浪费空间，太小又会频繁扩容
             */
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
            allocHandle.reset(config);

            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    do {
                        /**
                         * 1.调用子类的doReadMessages方法，读取数据包，并放入readBuf链表中，当成功读取时返回1
                         * 2.readBuf=List<Object> , 这里的doReadMessages方法是逻辑上的read方法。因为
                         * 对于socketServerChannel来说，监听到OP_Accept事件后 我们会通过 自定义的逻辑层面的read来获取结果
                         * 对于ServerSocketChannel来说，就是接收一个客户端Channel，添加到readBuf,对应的实现是：
                         * io.netty.channel.socket.nio.NioServerSocketChannel#doReadMessages(java.util.List)
                         *
                         * 对于SocketChannel来说，OP_Read事件发生的时候，通过逻辑层面的read方法来获取结果,就是读取客户端发送的数据，添加到readBuf
                         */
                        int localRead = doReadMessages(readBuf);
                        if (localRead == 0) {
                            break;
                        }
                        if (localRead < 0) {
                            closed = true;
                            break;
                        }
                        /**
                         *  递增已读取的消息数量
                         */
                        allocHandle.incMessagesRead(localRead);
                    } while (continueReading(allocHandle));//不能超过16次
                } catch (Throwable t) {
                    exception = t;
                }

                int size = readBuf.size();
                /**
                 *循环处理读取的数据包
                 */
                for (int i = 0; i < size; i ++) {
                    readPending = false;
                    /**
                     * 通过pipeline传播ChannelRead事件。
                     * Boss中的NioEventLoop 执行selector.slectNow()，当serverSocketChannel发生OP_Accept事件的时候就会返回
                     * 对于OP_Accept和OP_read事件，Netty都是使用  unsafe.read();来处理（具体查看NioEventLoop.processSelectedKey()中对事件的处理）
                     *
                     * 对于服务端的Unsafe.read()，这里会执行io.netty.channel.nio.
                     * AbstractNioMessageChannel.NioMessageUnsafe.read()方法，它会调用JDK底层的ServerSocketChannel.accept()
                     * 接收到客户端的连接后，将其封装成Netty的NioSocketChannel，再通过Pipeline将ChannelRead事件传播出去，
                     * 这样ServerBootstrapAcceptor就可以在ChannelRead回调里处理新的客户端连接了。
                     *
                     * 	ServerBootstrapAcceptor#channelRead(io.netty.channel.ChannelHandlerContext, java.lang.Object)
                     *
                     */
                    pipeline.fireChannelRead(readBuf.get(i));
                }
                readBuf.clear();
                /**
                 * 记录当前读取记录，一边下次分配合理内存
                 * 读取完毕的回调，有的Handle会根据本次读取的总字节数，自适应调整下次应该分配的缓冲区大小
                 */
                allocHandle.readComplete();
                // 通过pipeline传播ChannelReadComplete事件
                pipeline.fireChannelReadComplete();

                if (exception != null) {
                    closed = closeOnReadError(exception);

                    pipeline.fireExceptionCaught(exception);
                }

                if (closed) {
                    inputShutdown = true;
                    if (isOpen()) {
                        close(voidPromise());
                    }
                }
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();

        int maxMessagesPerWrite = maxMessagesPerWrite();
        while (maxMessagesPerWrite > 0) {
            Object msg = in.current();
            if (msg == null) {
                break;
            }
            try {
                boolean done = false;
                //获取配置中循环写的最大次数
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                    if (doWriteMessage(msg, in)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    maxMessagesPerWrite--;
                    /**
                     * 若发送成功，则将其从缓存链表中移除。 继续发送下一个缓存节点数据
                     */
                    in.remove();
                } else {
                    break;
                }
            } catch (Exception e) {
                if (continueOnWriteError()) {
                    maxMessagesPerWrite--;
                    in.remove(e);
                } else {
                    throw e;
                }
            }
        }
        if (in.isEmpty()) {
            // Wrote all messages.
            if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                //移除op_write事件
                key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
            }
        } else {
            // Did not write all messages.
            if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                //将op——write事件添加到兴趣事件集合中
                key.interestOps(interestOps | SelectionKey.OP_WRITE);
            }
        }
    }

    /**
     * Returns {@code true} if we should continue the write loop on a write error.
     */
    protected boolean continueOnWriteError() {
        return false;
    }

    protected boolean closeOnReadError(Throwable cause) {
        if (!isActive()) {
            // If the channel is not active anymore for whatever reason we should not try to continue reading.
            return true;
        }
        if (cause instanceof PortUnreachableException) {
            return false;
        }
        if (cause instanceof IOException) {
            // ServerChannel should not be closed even on IOException because it can often continue
            // accepting incoming connections. (e.g. too many open files)
            return !(this instanceof ServerChannel);
        }
        return true;
    }

    /**
     * Read messages into the given array and return the amount which was read.
     */
    protected abstract int doReadMessages(List<Object> buf) throws Exception;

    /**
     * Write a message to the underlying {@link java.nio.channels.Channel}.
     *
     * @return {@code true} if and only if the message has been written
     */
    protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}
