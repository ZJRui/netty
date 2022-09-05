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
package io.netty.util.concurrent;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract base class for {@link EventExecutorGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

    private final EventExecutor[] children;
    private final Set<EventExecutor> readonlyChildren;
    private final AtomicInteger terminatedChildren = new AtomicInteger();
    private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE);
    private final EventExecutorChooserFactory.EventExecutorChooser chooser;

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param threadFactory     the ThreadFactory to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        this(nThreads, threadFactory == null ? null : new ThreadPerTaskExecutor(threadFactory), args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor, Object... args) {
        this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param chooserFactory    the {@link EventExecutorChooserFactory} to use.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor,
                                            EventExecutorChooserFactory chooserFactory, Object... args) {
        checkPositive(nThreads, "nThreads");

        /**
         * NioEventLoopGroup 的构造器主要完成3件事情
         * （1）创建一定数量的NioEventLoop线程组并初始化
         * （2）创建线程选择器chooser。当获取线程时 通过选择器来获取
         * （3）创建线程工厂并构造线程执行器
         *
         * Netty的线程组除了NioEventLoopGroup 还有Netty通过JNI方式提供的一套由epoll模型实现的EpollEventLoopGroup线程组 以及
         * 其他IO多路复用模型线程组。对每个模型线程组 会有不同的newChild方法 来创建具体的线程组子类
         *
         */

        if (executor == null) {
            /**
             * 创建线程执行器 以及线程工厂。 这个线程执行器被 交给 newChild方法，
             * newChild方法中会创建NioEventLoop对象，所以执行器被交给了NioEventLoop,NioEventLoop 又是SingleThreadEventExecutor的子类
             *
             * SingleThreadEventExecutor#SingleThreadEventExecutor
             *
             * 在SingleThreadEventExecutor的io.netty.util.concurrent.SingleThreadEventExecutor#doStartThread()方法中
             * 这个executor线程池被用来执行 io.netty.channel.nio.NioEventLoop#run() 方法，NioEventLoop的run方法会不断的从任务队列中取出
             * 任务来执行。 因此这个线程池被用来执行 NioEventloop的run
             *
             *
             * 值得注意的时 这个线程池是 Netty实现的ThreadPerTaskExecutor 线程池，这个线程池在实现execute方法的是时候是 创建一个新的线程来执行 execute提交的任务。
             * 那么也就是说 假设我现在  for循环创建了两个 NioEventLoop ，每一个NioEventLoop中 又通过doStartThread方法 往 这里的ThreadPerTaskExecutor 线程池
             * 中提交了一个消费NioEventLoop的自身队列任务的 任务。因此 ThreadPerTaskExecutor的execute方法会为每个NioEventLoop对象
             * 创建一个新的线程来执行 execute提交的任务（这个任务就是NioEventLoop的run方法，for死循环处理NioEventLoop对象的自身的任务队列 ）
             *
             */
            executor = new ThreadPerTaskExecutor(newDefaultThreadFactory());
        }

        /**
         * 创建一定数量的EventExecutor
         * NioEventLoop就是 EventExecutor的实现类
         *
         *  SingleThreadEventExecutor (io.netty.util.concurrent)
         *             DefaultEventExecutor (io.netty.util.concurrent)
         *             SingleThreadEventLoop (io.netty.channel)
         *                 EpollEventLoop (io.netty.channel.epoll)
         *                 ThreadPerChannelEventLoop (io.netty.channel)
         *                 DefaultEventLoop (io.netty.channel)
         *                 NioEventLoop (io.netty.channel.nio)------------------》最常用
         *                 KQueueEventLoop (io.netty.channel.kqueu
         *
         */
        children = new EventExecutor[nThreads];

        for (int i = 0; i < nThreads; i ++) {
            boolean success = false;
            try {
                /**
                 *
                 * 问题一：newChild是一个abstract方法，为什么呢？
                 * Netty的线程组除了NioEventLoopGroup 还有Netty通过JNI方式提供的一套由epoll模型实现的EpollEventLoopGroup线程组 以及
                 * 其他IO多路复用模型线程组。对每个模型线程组 会有不同的newChild方法 来创建具体的线程组子类
                 *
                 *
                 * 2.注意这个args 数组，他的第一项是  io.netty.channel.nio.NioEventLoopGroup#NioEventLoopGroup(int, java.util.concurrent.Executor)
                 * 方法中传递过来的 SelectorProvider.provider()
                 *
                 * 也就是说 Boos或者worker各自的线程组 中的所有的Nio线程都会使用相同的 SelectorProvider，但是 SelectProvier可以创建 很多个Selector对象。
                 * 每一个NIO线程都会有自己的Selector对象。
                 *
                 * 3.由NioEventLoopGroup创建NioEventLoop类的实例
                 */
                children[i] = newChild(executor, args);
                success = true;
            } catch (Exception e) {
                // TODO: Think about if this is a good exception type
                throw new IllegalStateException("failed to create a child event loop", e);
            } finally {
                /**
                 * 当初始化失败时，需要优雅关闭，清理资源
                 */
                if (!success) {
                    for (int j = 0; j < i; j ++) {
                        children[j].shutdownGracefully();
                    }

                    for (int j = 0; j < i; j ++) {
                        EventExecutor e = children[j];
                        try {
                            //当线程没有终止时等待终止
                            while (!e.isTerminated()) {
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException interrupted) {
                            // Let the caller handle the interruption.
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        /**
         * 根据线程数创建选择器，选择器主要适用于next方法
         */
        chooser = chooserFactory.newChooser(children);

        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                if (terminatedChildren.incrementAndGet() == children.length) {
                    terminationFuture.setSuccess(null);
                }
            }
        };

        /**
         * 为每个EventLoop线程添加线程终止监听器
         */
        for (EventExecutor e: children) {
            e.terminationFuture().addListener(terminationListener);
        }

        Set<EventExecutor> childrenSet = new LinkedHashSet<EventExecutor>(children.length);
        Collections.addAll(childrenSet, children);
        /**
         * 创建执行器数组只读副本，在迭代查询时使用
         */
        readonlyChildren = Collections.unmodifiableSet(childrenSet);
    }

    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass());
    }

    @Override
    public EventExecutor next() {
        return chooser.next();
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return readonlyChildren.iterator();
    }

    /**
     * Return the number of {@link EventExecutor} this implementation uses. This number is the maps
     * 1:1 to the threads it use.
     */
    public final int executorCount() {
        return children.length;
    }

    /**
     * Create a new EventExecutor which will later then accessible via the {@link #next()}  method. This method will be
     * called for each thread that will serve this {@link MultithreadEventExecutorGroup}.
     *
     */
    protected abstract EventExecutor newChild(Executor executor, Object... args) throws Exception;

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        for (EventExecutor l: children) {
            l.shutdownGracefully(quietPeriod, timeout, unit);
        }
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        for (EventExecutor l: children) {
            l.shutdown();
        }
    }

    @Override
    public boolean isShuttingDown() {
        for (EventExecutor l: children) {
            if (!l.isShuttingDown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isShutdown() {
        for (EventExecutor l: children) {
            if (!l.isShutdown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isTerminated() {
        for (EventExecutor l: children) {
            if (!l.isTerminated()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        loop: for (EventExecutor l: children) {
            for (;;) {
                long timeLeft = deadline - System.nanoTime();
                if (timeLeft <= 0) {
                    break loop;
                }
                if (l.awaitTermination(timeLeft, TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
        }
        return isTerminated();
    }
}
