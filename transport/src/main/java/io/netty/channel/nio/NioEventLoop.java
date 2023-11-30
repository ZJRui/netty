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
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    /**
     * NioEventLoop有以下5个核心功能。
     * • 开启Selector并初始化。
     * • 把ServerSocketChannel注册到Selector上。
     * • 处理各种I/O事件，如OP_ACCEPT、OP_CONNECT、OP_READ、OP_WRITE事件。
     * • 执行定时调度任务。
     * • 解决JDK空轮询bug。
     */

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - https://bugs.openjdk.java.net/browse/JDK-6427854 for first few dev (unreleased) builds of JDK 7
    // - https://bugs.openjdk.java.net/browse/JDK-6527572 for JDK prior to 5.0u15-rev and 6u10
    // - https://github.com/netty/netty/issues/203
    static {
        if (PlatformDependent.javaVersion() < 7) {
            final String key = "sun.nio.ch.bugLevel";
            final String bugLevel = SystemPropertyUtil.get(key);
            if (bugLevel == null) {
                try {
                    AccessController.doPrivileged(new PrivilegedAction<Void>() {
                        @Override
                        public Void run() {
                            System.setProperty(key, "");
                            return null;
                        }
                    });
                } catch (final SecurityException e) {
                    logger.debug("Unable to get/set System Property: " + key, e);
                }
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     */
    private Selector selector;
    private Selector unwrappedSelector;
    private SelectedSelectionKeySet selectedKeys;

    private final SelectorProvider provider;

    private static final long AWAKE = -1L;
    private static final long NONE = Long.MAX_VALUE;

    // nextWakeupNanos is:
    //    AWAKE            when EL is awake
    //    NONE             when EL is waiting with no wakeup scheduled
    //    other value T    when EL is waiting with wakeup scheduled at time T
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);

    private final SelectStrategy selectStrategy;

    /**
     * 由于NioEventLoop需要同时处理I/O事件和非I/O任务，为了保证
     * 两者都能得到足够的CPU时间被执行，Netty提供了I/O比例供用户定
     * 制。如果I/O操作多于定时任务和Task，则可以将I/O比例调大，反之
     * 则调小，默认值为50%。
     */
    private volatile int ioRatio = 50;
    private int cancelledKeys;
    private boolean needsToSelectAgain;

    /**
     *
     * @param parent
     * @param executor
     * @param selectorProvider
     * @param strategy
     * @param rejectedExecutionHandler
     * @param taskQueueFactory
     * @param tailTaskQueueFactory
     */
    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory taskQueueFactory, EventLoopTaskQueueFactory tailTaskQueueFactory) {
        /**
         * 这里NioEventLoop继承自SingleThreadEventLoop
         */

        super(parent, executor, false, newTaskQueue(taskQueueFactory), newTaskQueue(tailTaskQueueFactory),
                rejectedExecutionHandler);
        this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");
        /**
         * 开启selector
         */
        final SelectorTuple selectorTuple = openSelector();
        this.selector = selectorTuple.selector;
        this.unwrappedSelector = selectorTuple.unwrappedSelector;
    }

    private static Queue<Runnable> newTaskQueue(
            EventLoopTaskQueueFactory queueFactory) {
        /**
         * QueueFactory是队列工厂，在NioEventLoop中 队列读是单线程操作。而队列写则可能是多线程操作，使用支持多生产者、
         * 单消费者的队列比较合适。默认是MpscChunkedArrayQueue队列
         */
        if (queueFactory == null) {
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            /**
             * 1.关于provider参考 io.netty.channel.socket.nio.NioSocketChannel#DEFAULT_SELECTOR_PROVIDER
             *
             */
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        /**
         * disable 为true表示没有开启优化开关
         */
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }
        /**
         *
         * 1.以下内容是开启了优化开关
         * Netty为Selector设置
         * 了 优 化 开 关 ， 如 果 开 启 优 化 开 关 ， 则 通 过 反 射 加 载
         * sun.nio.ch.SelectorImpl 对 象 ， 并 通 过 已 经 优 化 过 的
         * SelectedSelectionKeySet 替 换 sun.nio.ch.SelectorImpl 对 象 中 的
         * selectedKeys 和 publicSelectedKeys 两 个 HashSet 集 合 。 其 中 ，
         * selectedKeys为就绪Key的集合，拥有所有操作事件准备就绪的选择
         * Key；publicSelectedKeys 为 外 部 访 问 就 绪 Key 的 集 合 代 理 ， 由
         * selectedKeys集合包装成不可修改的集合。
         * SelectedSelectionKeySet具体做了什么优化呢？主要是数据结构
         * 改变了，用数组替代了HashSet，重写了add()和iterator()方法，使
         * 数 组 的 遍 历 效 率 更 高 。 开 启 优 化 开 关 ， 需 要 将 系 统 属 性
         * io.netty.noKeySetOptimization设置为true。
         *
         *
         * 2.在阅读netty源码的时候对selectedKeys一直有个疑问？
         * select(boolean b); 方法选择的时候是如何将key放到集合selectedKeys里面去的
         * 经过debug发现在WindowsSelectorImpl.processFDSet方法里面有selectedKeys.add(sk);
         * 为什么调用了io.netty.channel.nio.SelectedSelectionKeySet#add 方法；
         * 而且selectedKey 是SelectorImpl 里面的属性， protected Set<SelectionKey> selectedKeys = new HashSet(); 是不是很奇怪，
         * 为什么调用到了比人的方法里面去了？
         *
         * 经过分析NioEventLoop里面的selectedKeys属性， 看看是如何进行初始化的之前真的没有思考太多， 这次发现了一个重要的地方
         * NioEventLoop.openSelector()
         *
         * 内部采用了AccessController.doPriviledged 回调函数，绕过权限的检查，
         * 通过反射来修改了SelectorImpl类里面的selectedKeys属性的类型；
         *
         * 就是说之前是HashSet的实例对象， 现在改成了自己的类即为： SelectedSelectionKeySet (继承了AbstractSet)  不就是一个Set类型吗；
         *
         * 可以通-Dio.netty.noKeySetOptimization=true 禁止选择的key优化
         */

        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    /**
                     *在阅读netty源码的时候对selectedKeys一直有个疑问？
                     * select(boolean b); 方法选择的时候是如何将key放到集合selectedKeys里面去的
                     * 经过debug发现在WindowsSelectorImpl.processFDSet方法里面有selectedKeys.add(sk);
                     * 为什么调用了io.netty.channel.nio.SelectedSelectionKeySet#add 方法；
                     * 而且selectedKey 是SelectorImpl 里面的属性， protected Set<SelectionKey> selectedKeys = new HashSet(); 是不是很奇怪，
                     * 为什么调用到了比人的方法里面去了？
                     *
                     * 经过分析NioEventLoop里面的selectedKeys属性， 看看是如何进行初始化的之前真的没有思考太多， 这次发现了一个重要的地方
                     * NioEventLoop.openSelector()
                     *
                     * 内部采用了AccessController.doPriviledged 回调函数，绕过权限的检查，
                     * 通过反射来修改了SelectorImpl类里面的selectedKeys属性的类型；
                     *
                     * 就是说之前是HashSet的实例对象， 现在改成了自己的类即为： SelectedSelectionKeySet (继承了AbstractSet)  不就是一个Set类型吗；
                     *
                     * 可以通-Dio.netty.noKeySetOptimization=true 禁止选择的key优化
                     */
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    /**
                     * 通过反射替换
                     */

                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        /**
         * 把selectedKeySet 赋值给NioEventLoop的属性，并返回Selector元数据
         */
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        ObjectUtil.checkNotNull(ch, "ch");
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        ObjectUtil.checkNotNull(task, "task");

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
     * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
     * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
     * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    @Override
    public Iterator<Channel> registeredChannelsIterator() {
        assert inEventLoop();
        final Set<SelectionKey> keys = selector.keys();
        if (keys.isEmpty()) {
            return ChannelsReadOnlyIterator.empty();
        }
        return new Iterator<Channel>() {
            final Iterator<SelectionKey> selectionKeyIterator =
                    ObjectUtil.checkNotNull(keys, "selectionKeys")
                            .iterator();
            Channel next;
            boolean isDone;

            @Override
            public boolean hasNext() {
                if (isDone) {
                    return false;
                }
                Channel cur = next;
                if (cur == null) {
                    cur = next = nextOrDone();
                    return cur != null;
                }
                return true;
            }

            @Override
            public Channel next() {
                if (isDone) {
                    throw new NoSuchElementException();
                }
                Channel cur = next;
                if (cur == null) {
                    cur = nextOrDone();
                    if (cur == null) {
                        throw new NoSuchElementException();
                    }
                }
                next = nextOrDone();
                return cur;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("remove");
            }

            private Channel nextOrDone() {
                Iterator<SelectionKey> it = selectionKeyIterator;
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    if (key.isValid()) {
                        Object attachment = key.attachment();
                        if (attachment instanceof AbstractNioChannel) {
                            return (AbstractNioChannel) attachment;
                        }
                    }
                }
                isDone = true;
                return null;
            }
        };
    }

    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                //判断key是否有效
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                //在旧的selector上触发的事件需要取消
                int interestOps = key.interestOps();
                key.cancel();
                //b把channel重新注册到新的selector上
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    @Override
    protected void run() {
        /**
         * 1.run()方法主要分三部分：
         * (1)select(boolean oldWakenUp)，用来轮询 就 绪 的 Channel；
         * (2)process  SelectedKeys ， 用 来 处 理 轮 询 到 的SelectionKey；
         * (3)runAllTasks，用来执行队列任务。
         *
         *
         */
        int selectCnt = 0;
        for (;;) {
            try {
                int strategy;
                try {
                    /**
                     * 1.根据是否由任务获取策略，默认策略，当由任务时 返回selector.selectNow
                     * 当无任务时返回SelectStrategy.select=-1
                     *
                     * 2. 这里会在Selector对象上执行selector.selectedKeys() 从而得到就绪的key，
                     * 只不过这里 selector.selectedKeys()这个操作是以 selectNowSupplier 函数的形式
                     */
                    strategy = selectStrategy.calculateStrategy(selectNowSupplier, hasTasks());
                    switch (strategy) {
                    case SelectStrategy.CONTINUE:
                        continue;

                    case SelectStrategy.BUSY_WAIT:
                        // fall-through to SELECT since the busy-wait is not supported with NIO

                    case SelectStrategy.SELECT:
                        /**
                         * 定时任务的触发时间
                         */
                        long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
                        if (curDeadlineNanos == -1L) {
                            curDeadlineNanos = NONE; // nothing on the calendar
                        }
                        nextWakeupNanos.set(curDeadlineNanos);
                        try {
                            if (!hasTasks()) {
                                /**
                                 * 轮询看看是否由准备就绪的channel。 在轮询过程中 会调用Nio Selector的selectNow
                                 * 和select（timeoutMills）方法。由于对这两个方法的调用进行了很明显的区分，因此这两个方法的条件
                                 * 也有所不同
                                 */
                                strategy = select(curDeadlineNanos);
                            }
                        } finally {
                            // This update is just to help block unnecessary selector wakeups
                            // so use of lazySet is ok (no race condition)
                            nextWakeupNanos.lazySet(AWAKE);
                        }
                        // fall through
                    default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    selectCnt = 0;
                    handleLoopException(e);
                    continue;
                }

                /**
                 * 检测次数增加，这个参数主要用来判断是否为空轮询
                 *
                 * 2.，runAllTasks：主要目的是执行taskQueue队列和定时
                 * 任务队列中的任务，如心跳检测、异步写操作等。首先NioEventLoop
                 * 会根据ioRatio（I/O事件与taskQueue运行的时间占比）计算任务执行
                 * 时 长 。 由 于 一 个 NioEventLoop 线 程 需 要 管 理 很 多 Channel ， 这 些
                 * Channel的任务可能非常多，若要都执行完，则I/O事件可能得不到及
                 * 时处理，因此每执行64个任务后就会检测执行任务的时间是否已用
                 * 完，如果执行任务的时间用完了，就不再执行后续的任务了
                 */
                selectCnt++;
                cancelledKeys = 0;
                needsToSelectAgain = false;
                final int ioRatio = this.ioRatio;
                boolean ranTasks;
                if (ioRatio == 100) {
                    try {
                        if (strategy > 0) {
                            processSelectedKeys();
                        }
                    } finally {
                        // Ensure we always run tasks.
                        ranTasks = runAllTasks();
                    }
                } else if (strategy > 0) {
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        /**
                         * ，runAllTasks：主要目的是执行taskQueue队列和定时
                         * 任务队列中的任务，如心跳检测、异步写操作等
                         * 首先NioEventLoop会根据ioRatio（I/O事件与taskQueue运行的时间占比）计算任务执行
                         * 时 长 。 由 于 一 个 NioEventLoop 线 程 需 要 管 理 很 多 Channel ， 这 些
                         * Channel的任务可能非常多，若要都执行完，则I/O事件可能得不到及
                         * 时处理，因此每执行64个任务后就会检测执行任务的时间是否已用
                         * 完，如果执行任务的时间用完了，就不再执行后续的任务了
                         *
                         * 按照一定的时间比例执行任务，由可能遗留一部分任务等待下次执行
                         */
                        // Ensure we always run tasks.
                        final long ioTime = System.nanoTime() - ioStartTime;
                        /**
                         * 由于NioEventLoop需要同时处理I/O事件和非I/O任务，为了保证
                         * 两者都能得到足够的CPU时间被执行，Netty提供了I/O比例供用户定
                         * 制。如果I/O操作多于定时任务和Task，则可以将I/O比例调大，反之
                         * 则调小，默认值为50%。
                         * Task的执行时间根据本次I/O操作的执行时间计算得来
                         */
                        ranTasks = runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                } else {
                    ranTasks = runAllTasks(0); // This will run the minimum number of tasks
                }

                if (ranTasks || strategy > 0) {
                    if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS && logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                                selectCnt - 1, selector);
                    }
                    selectCnt = 0;
                } else if (unexpectedSelectorWakeup(selectCnt)) { // Unexpected wakeup (unusual case)
                    selectCnt = 0;
                }
            } catch (CancelledKeyException e) {
                // Harmless exception - log anyway
                if (logger.isDebugEnabled()) {
                    logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                            selector, e);
                }
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                handleLoopException(t);
            } finally {
                // Always handle shutdown even if the loop processing threw an exception.
                try {
                    if (isShuttingDown()) {
                        closeAll();
                        if (confirmShutdown()) {
                            return;
                        }
                    }
                } catch (Error e) {
                    throw e;
                } catch (Throwable t) {
                    handleLoopException(t);
                }
            }
        }//end for
    }

    // returns true if selectCnt should be reset
    private boolean unexpectedSelectorWakeup(int selectCnt) {
        if (Thread.interrupted()) {
            // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
            // As this is most likely a bug in the handler of the user or it's client library we will
            // also log it.
            //
            // See https://github.com/netty/netty/issues/2426
            if (logger.isDebugEnabled()) {
                logger.debug("Selector.select() returned prematurely because " +
                        "Thread.currentThread().interrupt() was called. Use " +
                        "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
            }
            return true;
        }
        /**
         * 在 一定的时间内，连续select次数大于或等于512次未跳出循环，此时表示selector进入空轮询，需要重新构建selector 并跳出循环
         */
        if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
            // The selector returned prematurely many times in a row.
            // Rebuild the selector to work around the problem.
            logger.warn("Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                    selectCnt, selector);
            rebuildSelector();
            return true;
        }
        return false;
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private void processSelectedKeys() {
        /**
         * 判断优化后端selectedKeys是否为空。
         */
        if (selectedKeys != null) {
            //优化处理
            processSelectedKeysOptimized();
        } else {
            //原始处理
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        //当移除次数大于256时
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }
        /**
         * processSelectedKeys 主要处理第一部分轮询得到的就绪的key，并取出这些SelectionKey及其附件attachment。
         * 附件由两种类型： （1）AbstractNioChannel （2）NioTask。 其中第二种附件在Netty内部未使用，因此只分析AbstractNioChannel。
         *
         *
         * 根据key的实际爱你类型触发AbstractNioChannel的unsafe的不同方法。 这些方法主要是io的读写操作
         */

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    private void processSelectedKeysOptimized() {
        /**
         * processSelectedKeys 主要处理第一部分轮询得到的就绪的key，并取出这些SelectionKey及其附件attachment。
         * 附件由两种类型： （1）AbstractNioChannel （2）NioTask。 其中第二种附件在Netty内部未使用，因此只分析AbstractNioChannel。
         *
         *
         * 根据key的实际爱你类型触发AbstractNioChannel的unsafe的不同方法。 这些方法主要是io的读写操作
         */
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            /**
             * 将selectedKeys.keys[i]置为null，并快速被JVM回收。 无需等到调用其重置再去回收，因为
             * key的attachment比较大，很容易造成内存泄露
             */
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;

            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
                //根据key的就绪时间触发对应的事件方法
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            /**
             * 判断是否应该再次轮询。
             * 每当256个channel从selector上移除时 就标记 needsToSelectAgain为true
             */
            if (needsToSelectAgain) {
                //清空i+1 之后的selectedKeys
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);
                //重新调用selectNow方法
                selectAgain();
                i = -1;
            }
        }
    }

    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        /**
         * 1.processSelectedKeys 主要处理第一部分轮询得到的就绪的key，并取出这些SelectionKey及其附件attachment。
         * 附件由两种类型： （1）AbstractNioChannel （2）NioTask。 其中第二种附件在Netty内部未使用，因此只分析AbstractNioChannel。
         *
         * 根据key的实际爱你类型触发AbstractNioChannel的unsafe的不同方法。 这些方法主要是io的读写操作
         *
         *
         *
         * 2.Boss和worker的EventLoop都有自己的selector. 对于NioEventLoop来说，他只需要知道channel和这个channel上感兴趣的事件就好了。
         * 比如对于Boss来说，channel就是ServerSocketChannel，感兴趣的事件就是OP_Accept,对于worker来说，channel就是SocketChannel，感兴趣的事件就是OP_Read和OP_Write。
         *
         * 不管是Boss还是Work ，每个EventLoop作为线程都会不断的在run方法中循环调用selector.selectedKeys()方法，这个方法会返回所有已经就绪的channel。
         *
         *
         *
         * 3.当调用ServerBootstrap.bind()方法时，Netty会创建ServerSocketChannel，
         * 并把它注册到BossGroup的NioEventLoop的Selector多路复用器，最后再绑定到本地端口。
         * 这样Netty就可以接收客户端的连接了，当有新的连接接入时，Selector会监听到并返回准备就绪的Channel，
         * NioEventLoop会处理这些事件，详见NioEventLoop.processSelectedKey()方法。
         * 由于事件类型是OP_ACCEPT，因此会调用Unsafe.read()处理
         *
         * 对于ServerSocketChannel只关心OP_ACCEPT事件
         *
         * 这个Unsafe接口有两大实现，分别是服务端Channel的Unsafe和客户端Channel的Unsafe。
         * 前者的read负责接收SocketChannel连接，后者的read负责读取对端发送的数据并封装成ByteBuf。
         *
         *
         * 对于服务端的Unsafe.read()，这里会执行io.netty.channel.nio.AbstractNioMessageChannel.
         * NioMessageUnsafe.read()方法，它会调用JDK底层的ServerSocketChannel.accept()接收到客户端的连接后，
         * 将其封装成Netty的NioSocketChannel，再通过Pipeline将ChannelRead事件传播出去，
         * 这样ServerBootstrapAcceptor就可以在ChannelRead回调里处理新的客户端连接了。
         *
         *
         *
         */

        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop == this) {
                // close the channel if the key is not valid anymore
                unsafe.close(unsafe.voidPromise());
            }
            return;
        }

        try {
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                ch.unsafe().forceFlush();
            }

            // 数据可读、有新的连接接入
            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {

                /**
                 *
                 * 1.对于ServerSocketChannel只关心OP_ACCEPT事件
                 *
                 * 这个Unsafe接口有两大实现，分别是服务端Channel的Unsafe和客户端Channel的Unsafe。
                 * 前者的read负责接收SocketChannel连接，后者的read负责读取对端发送的数据并封装成ByteBuf。
                 *
                 *
                 * 对于服务端的Unsafe.read()，这里会执行io.netty.channel.nio.AbstractNioMessageChannel.
                 * NioMessageUnsafe.read()方法，它会调用JDK底层的ServerSocketChannel.accept()接收到客户端的连接后，
                 * 将其封装成Netty的NioSocketChannel，再通过Pipeline将ChannelRead事件传播出去，
                 * 这样ServerBootstrapAcceptor就可以在ChannelRead回调里处理新的客户端连接了。
                 *
                 *
                 * 2.AbstractNioByteChannel的内部类NioByteUnsafe#read SocketChannel对应的读事件处理流程，即IO读的处理实现。
                 * AbstractNioMessageChannel的内部类NioMessageUnsafe#read  ServerSocketChannle对应的读事件处理流程。
                 */
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            default:
                 break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        /**
         *WindowsSelectorImpl
         * 的wakeup源码如下
         *     public Selector wakeup() {
         *         synchronized(this.interruptLock) {
         *             if (!this.interruptTriggered) {
         *                 this.setWakeupSocket();
         *                 this.interruptTriggered = true;
         *             }
         *
         *             return this;
         *         }
         *     }
         *由上述代码可以发现，多次同时调用wakeup()方法与调用一次没
         * 有区别，因为interruptTriggered第一次调用后就为true，后续再调
         * 用会立刻返回。在默认情况下，其他线程添加任务到taskQueue队列中
         * 后，会调用NioEventLoop的wakeup()方法
         */
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            selector.wakeup();
        }
    }

    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        return selector.selectNow();
    }

    private int select(long deadlineNanos) throws IOException {
        /**
         *  若 taskQueue 队 列 中 有 任 务 ， 且 从 EventLoop 线 程 进 入
         * select()方法开始后，一直无其他线程触发唤醒动作，则需要调用
         * selectNow() 方 法 ， 并 立 刻 返 回 。 因 为 在 运 行 select(boolean
         * oldWakenUp) 之 前 ， 若 有 线 程 触 发 了 wakeUp 动 作 ， 则 需 要 保 证
         * tsakQueue队列中的任务得到了及时处理，防止等待timeoutMillis超
         * 时后处理。
         */
        if (deadlineNanos == NONE) {
            return selector.select();
        }
        // Timeout will only be 0 if deadline is within 5 microsecs
        long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L;
        /**
         * 当select(timeoutMillis)阻塞运行时，在以下4种情况下会
         * 正常唤醒线程：其他线程执行了wakeUp唤醒动作、检测到就绪Key、遇
         * 上空轮询、超时自动醒来。唤醒线程后，除了空轮询会继续轮询，其
         * 他正常情况会跳出循环。具体代码解读如下：
         */
        return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis);
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
