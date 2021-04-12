/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.dubbo.common.timer;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 *
 * <h3>Tick Duration</h3>
 * <p>
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.  {@link HashedWheelTimer}, on every tick, will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy of the execution timing by
 * specifying smaller or larger tick duration in the constructor.  In most
 * network applications, I/O timeout does not need to be accurate.  Therefore,
 * the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 *
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 * <p>
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 *
 * <h3>Do not create many instances.</h3>
 * <p>
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance for every connection.
 *
 * <h3>Implementation Details</h3>
 * <p>
 * {@link HashedWheelTimer} is based on
 * <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="http://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 */
public class HashedWheelTimer implements Timer {

    /**
     * may be in spi?
     */
    public static final String NAME = "hased";

    private static final Logger logger = LoggerFactory.getLogger(HashedWheelTimer.class);

    //时间轮在JVM中的实例数
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();
    //时间轮在JVM中的最大实例数
    private static final int INSTANCE_COUNT_LIMIT = 64;
    // CAS原子性的操作workerState字段
    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");

    //执行延迟任务，时钟tick的转动
    private final Worker worker = new Worker();
    //执行时间轮中延迟任务的线程（包括tick的转动）
    private final Thread workerThread;

    private static final int WORKER_STATE_INIT = 0;
    private static final int WORKER_STATE_STARTED = 1;
    private static final int WORKER_STATE_SHUTDOWN = 2;

    /**
     * 0 - init, 1 - started, 2 - shut down
     * 时间轮的工作线程状态（需要原子操作）
     */
    @SuppressWarnings({"unused", "FieldMayBeFinal"})
    private volatile int workerState;

    //时钟间隔 纳秒
    private final long tickDuration;
    //时间轮中的环形数组
    private final HashedWheelBucket[] wheel;
    //用于定位延时任务对应的环形数组中的位置  tick & mask = index
    private final int mask;
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);
    //调用newTimeOut向时间轮新增延迟任务 会放到这个缓冲队列中
    private final Queue<HashedWheelTimeout> timeouts = new LinkedBlockingQueue<>();
    //所有被取消的延时任务都会加入到该队列中
    private final Queue<HashedWheelTimeout> cancelledTimeouts = new LinkedBlockingQueue<>();
    private final AtomicLong pendingTimeouts = new AtomicLong(0);
    // 最大允许等待任务数
    private final long maxPendingTimeouts;

    //时间轮启动 所需要的时间（用于后续计算延迟任务的执行时间点deadline）
    private volatile long startTime;

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}), default tick duration, and
     * default number of ticks per wheel.
     */
    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}) and default number of ticks
     * per wheel.
     *
     * @param tickDuration the duration between tick
     * @param unit         the time unit of the {@code tickDuration}
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}).
     *
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    /**
     * Creates a new timer with the default tick duration and default number of
     * ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public HashedWheelTimer(ThreadFactory threadFactory) {
        this(threadFactory, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new timer with the default number of ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, -1);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory      a {@link ThreadFactory} that creates a
     *                           background {@link Thread} which is dedicated to
     *                           {@link TimerTask} execution.
     * @param tickDuration       the duration between tick
     * @param unit               the time unit of the {@code tickDuration}
     * @param ticksPerWheel      the size of the wheel
     * @param maxPendingTimeouts The maximum number of pending timeouts after which call to
     *                           {@code newTimeout} will result in
     *                           {@link java.util.concurrent.RejectedExecutionException}
     *                           being thrown. No maximum pending timeouts limit is assumed if
     *                           this value is 0 or negative.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel,
            long maxPendingTimeouts) {

        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        if (tickDuration <= 0) {
            throw new IllegalArgumentException("tickDuration must be greater than 0: " + tickDuration);
        }
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }

        // Normalize ticksPerWheel to power of two and initialize the wheel.
        // 创建时间轮，需要保证时间轮中的槽位为2的次幂（目的是通过&运算 实现 取模逻辑）
        // 默认512个时钟槽位
        wheel = createWheel(ticksPerWheel);
        //计算延时任务 对应的 槽位索引  tick & mask = 对应的槽索引
        mask = wheel.length - 1;

        // Convert tickDuration to nanos.
        // 时钟间隔  表示 多久 tick指针走一下（时间轮中的时间单位为纳秒）
        this.tickDuration = unit.toNanos(tickDuration);

        // Prevent overflow.
        //防止时钟周期溢出 时间轮中的时间单位为纳秒 用Long类型存储，时钟周期如果超过了Long.MAX_VALUE会溢出。
        if (this.tickDuration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException(String.format(
                    "tickDuration: %d (expected: 0 < tickDuration in nanos < %d",
                    tickDuration, Long.MAX_VALUE / wheel.length));
        }
        //创建执行时间轮中延迟任务的线程（包括tick的转动）
        workerThread = threadFactory.newThread(worker);
        // 最大允许等待任务数
        this.maxPendingTimeouts = maxPendingTimeouts;

        //时间轮在JVM中的实例数 （不能创建超过64个时间轮实例）
        if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT &&
                WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
            //时间轮实例数超过64  则打印error日志 创建太多时间轮实例
            reportTooManyInstances();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            // This object is going to be GCed and it is assumed the ship has sailed to do a proper shutdown. If
            // we have not yet shutdown then we want to make sure we decrement the active instance count.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }
        }
    }

    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException(
                    "ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }
        // 时钟槽位不能超过 2^30
        if (ticksPerWheel > 1073741824) {
            throw new IllegalArgumentException(
                    "ticksPerWheel may not be greater than 2^30: " + ticksPerWheel);
        }

        //保证时钟槽位一定是不小于 ticksPerWheel 的最小 2 次幂
        //比如ticksPerWheel指定的是5  那么计算出来的最终时钟槽位是8
        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        //创建时间轮的环形数组，数组中的存放的就是时钟槽 类型为HashedWheelBucket（不带哨兵节点的双向链表保存HashedWheelTimeout）
        //kafka中的时间轮实现 是带有 哨兵节点的双向循环列表
        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        //初始化时钟槽
        for (int i = 0; i < wheel.length; i++) {
            wheel[i] = new HashedWheelBucket();
        }
        return wheel;
    }

    //改进netty的实现  采用JDK HashMap 扩容 tableSizeFor 的实现
    //需要保证环形数组个数为2的次幂 这样可以保证环形数组一定是不小于 ticksPerWheel 的最小 2 次幂
    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = ticksPerWheel - 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 1;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 2;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 4;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 8;
        normalizedTicksPerWheel |= normalizedTicksPerWheel >>> 16;
        return normalizedTicksPerWheel + 1;
    }

    /**
     * Starts the background thread explicitly.  The background thread will
     * start automatically on demand even if you did not call this method.
     * 懒启动
     * @throws IllegalStateException if this timer has been
     *                               {@linkplain #stop() stopped} already
     */
    public void start() {
        switch (WORKER_STATE_UPDATER.get(this)) {
            case WORKER_STATE_INIT:
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    //启动worker线程 线程启动后会设置startTime
                    workerThread.start();
                }
                break;
            case WORKER_STATE_STARTED:
                break;
            case WORKER_STATE_SHUTDOWN:
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }

        // Wait until the startTime is initialized by the worker.
        // 等待时间轮worker线程的启动
        while (startTime == 0) {
            try {
                //worker线程启动后，会在worker线程里执行countDown，接触这里的阻塞
                startTimeInitialized.await();
            } catch (InterruptedException ignore) {
                // Ignore - it will be ready very soon.
            }
        }
    }

    /**
     * 如何停止一个运行中的线程
     * 注意要停止一个运行中的线程  需要在线程停止后  执行清理相应业务资源的动作（需要在停止线程中定义）参考worker线程
     * 通过一个state字段 原子更新，停止线程中增加对state的原子判断
     * */
    @Override
    public Set<Timeout> stop() {
        //在worker线程中 不能执行时间轮停止的操作
        // worker中会执行延时任务，延时任务是不能够停止时间轮的
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(
                    HashedWheelTimer.class.getSimpleName() +
                            ".stop() cannot be called from " +
                            TimerTask.class.getSimpleName());
        }

        //通过workerState字段 控制worker线程的启动，停止
        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            // workerState can be 0 or 2 at this moment - let it always be 2.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }

            return Collections.emptySet();
        }

        try {
            boolean interrupted = false;
            //在其他线程中 停止 worker线程 （通过workerState字段 控制worker线程的启动，停止）
            while (workerThread.isAlive()) {
                workerThread.interrupt();
                try {
                    //等待workerThread结束 worker状态现在是shutdown状态 会退出do-while循环 执行清理动作(获取还没来得及执行的延时任务)
                    //此处 等待worker线程 清理动作执行完毕
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            INSTANCE_COUNTER.decrementAndGet();
        }
        //返回所有未处理的延时任务
        return worker.unprocessedTimeouts();
    }

    @Override
    public boolean isStop() {
        return WORKER_STATE_SHUTDOWN == WORKER_STATE_UPDATER.get(this);
    }

    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        //新添加进来的延时任务 全部都放在timeouts队列中缓存 等待放入时间轮中
        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();
        // timeouts中等待执行的延时任务数 超过maxPendingTimeouts时 则抛出异常RejectedExecutionException
        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts ("
                    + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending "
                    + "timeouts (" + maxPendingTimeouts + ")");
        }

        //懒启动时间轮，为了防止在没有延时任务的情况下 时间轮空转。
        // 所以在当延时任务 提交到 时间轮的时候 才启动时间轮
        start();

        // Add the timeout to the timeout queue which will be processed on the next tick.
        // During processing all the queued HashedWheelTimeouts will be added to the correct HashedWheelBucket.
        // 计算延迟任务执行的时间点 deadline
        // 因为时间轮是懒启动，所以时间轮启动的时间 也需要算进 任务的延迟时间里
        // 延时任务执行时间 = 当前系统时间 + 延迟时间 - 时间轮启动的时间。
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        // Guard against overflow.
        //防止deadline溢出
        if (delay > 0 && deadline < 0) {
            deadline = Long.MAX_VALUE;
        }

        //创建延时任务句柄
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        //将HashedWheelTimeout延时任务句柄 添加到timeouts队列中缓冲 注意这时还没有添加到时间轮中
        timeouts.add(timeout);
        //返回延时任务句柄给外部线程
        return timeout;
    }

    /**
     * Returns the number of pending timeouts of this {@link Timer}.
     */
    public long pendingTimeouts() {
        return pendingTimeouts.get();
    }

    private static void reportTooManyInstances() {
        String resourceType = ClassUtils.simpleClassName(HashedWheelTimer.class);
        logger.error("You are creating too many " + resourceType + " instances. " +
                resourceType + " is a shared resource that must be reused across the JVM," +
                "so that only a few instances are created.");
    }

    private final class Worker implements Runnable {
        // 时间轮调用stop()方法停止时  还没来得及处理的延时任务
        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();
        //时钟tick是绝对值 不是相对 会一直累加
        private long tick;

        @Override
        public void run() {
            // Initialize the startTime.
            startTime = System.nanoTime();
            if (startTime == 0) {
                // We use 0 as an indicator for the uninitialized value here, so make sure it's not 0 when initialized.
                startTime = 1;
            }

            // Notify the other threads waiting for the initialization at start().
            // 通知 org.apache.dubbo.common.timer.HashedWheelTimer.start方法 解除阻塞
            // 通知其他等待worker线程初始化的线程。
            startTimeInitialized.countDown();

            do {
                //等待指针到达下一个tick,并返回下一个tick的时间点deadLine
                final long deadline = waitForNextTick();
                if (deadline > 0) {
                    //获取当前tick在时间轮中的时间槽索引
                    // 为什么这里是当前tick 而不是deanline对应的tick + 1
                    // tick对应的是时间槽，时间槽里存放的是延时任务
                    // tick = 0 对应的时间槽里存放的是延时时间在[0,1)的任务 deadline = 1 需要执行timeout.deadline <= deadline的延时任务
                    // tick = 1 对应的时间槽里存放的是延时时间在[1,2)的任务 deadline = 2
                    // tick = 2 对应的时间槽里存放的是延时时间在[2,3)的任务 deadline = 3
                    // tick = 3 对应的时间槽里存放的是延时时间在[3,4)的任务 deadline = 4
                    // 比如tick = 2 时间槽里放的是延迟时间[2,3)的任务 这时的deadline = 3
                    // 这时时间槽里需要执行的任务条件是 timeout.deadline <= deadline
                    // 这样做的目的是为了使延迟时间在[2,3)之间的任务得到执行 所以时间轮执行任务的时间并不是精确的 基本都会晚一个tickDurtion
                    // 如果tick = 0 只执行deadline = 0的任务 tick =1 只执行 deadline = 1的任务 那么(0,1)之间的任务就不会得到执行了
                    // 所以deadline对应的时钟tick 总是比 当前tick多一
                    int idx = (int) (tick & mask);
                    // 处理被取消的延时任务（移除）
                    processCancelledTasks();
                    // 获取当前tick 对应的时间槽
                    HashedWheelBucket bucket =
                            wheel[idx];
                    // 将timeouts缓冲队列中的延时任务  加入到时间轮中
                    transferTimeoutsToBuckets();
                    // 执行当前tick时间槽中 到期的延时任务 到期时间点为deadline 对应的是tick + 1
                    bucket.expireTimeouts(deadline);
                    // tick向前推进一位
                    tick++;
                }
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);//通过workerState字段 控制worker线程的启停

            // worker线程停止的时候 执行下列 收尾动作

            // Fill the unprocessedTimeouts so we can return them from stop() method.
            // 当时间轮停止的时候，获取时间轮中所有没来得及处理的延时任务
            for (HashedWheelBucket bucket : wheel) {
                bucket.clearTimeouts(unprocessedTimeouts);
            }

            // 当时间轮停止的时候，获取刚刚提交 但是还没有来得及加入时间轮的延时任务
            for (; ; ) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (!timeout.isCancelled()) {
                    unprocessedTimeouts.add(timeout);
                }
            }

            // 处理取消的延时任务
            processCancelledTasks();
        }

        private void transferTimeoutsToBuckets() {
            // transfer only max. 100000 timeouts per tick to prevent a thread to stale the workerThread when it just
            // adds new timeouts in a loop.
            // 每个tick最多只能处理10万个 延时任务。 防止当前tick的延时任务过多 阻塞worker线程
            // 每次tick处理 从timeouts缓冲队列中拿出最多10万个新提交的延时任务 放入时间轮中
            for (int i = 0; i < 100000; i++) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) {
                    // Was cancelled in the meantime.
                    continue;
                }

                // 一共需要走多少个时钟tick
                long calculated = timeout.deadline / tickDuration;
                // 需要经历多少个时钟周期 = 剩余需要走的时钟tick / 时间轮中时间槽的个数
                // 一个时钟周期 表示 时钟tick需要走完一轮
                timeout.remainingRounds = (calculated - tick) / wheel.length;

                // Ensure we don't schedule for past.
                // calculated < 当前tick  代表 延时任务的执行时间 已经过去了
                // 如果任务的执行时间已经过了 那就把任务放到当前tick中执行
                // 因为时间轮中的任务并不能够保证及时执行，假如有一个任务执行的时间特别长，
                // 那么任务在 timeouts 队列里已经过了执行时间，也没有关系，
                // Worker 会将这些任务直接加入当前HashedWheelBucket 中，所以过期的任务并不会被遗漏。
                /**
                 * 这里也就说明了当向时间轮添加任务时 为什么不是直接添加 而是先添加到timeouts队列中缓存
                 * 因为时间轮是单线程worker执行的 包括tick的转动，延时任务的执行，如果是直接添加到时间轮中
                 * 有可能calculated会 小于 当前tick  导致 该延时任务得不到执行。
                 *
                 * 那么放到timeouts队列中缓冲，就可以在每次执行bucket时间槽中的延时任务时 先将timeouts中的延时任务
                 * 添加到时间轮中，发现如果任务过期 则将 任务添加到 当前时间槽bucket中，
                 * 然后在处理bucket中的延时任务。保证过期的延时任务不会被遗漏。
                 *
                 * */
                final long ticks = Math.max(calculated, tick);
                // 根据延时任务总共需要走的时钟tick & mask 得到 延时任务对应的时间槽索引
                int stopIndex = (int) (ticks & mask);

                // 将延时任务放入对应的时间槽中
                HashedWheelBucket bucket = wheel[stopIndex];
                bucket.addTimeout(timeout);
            }
        }

        private void processCancelledTasks() {
            //从cancelledTimeouts队列中取出被取消的延时任务，然后从时间槽中删除
            for (; ; ) {
                //所有被取消的延时任务都会加入到cancelledTimeouts队列中
                HashedWheelTimeout timeout = cancelledTimeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                try {
                    //从时间槽中删除
                    timeout.remove();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("An exception was thrown while process a cancellation task", t);
                    }
                }
            }
        }

        /**
         * calculate goal nanoTime from startTime and current tick number,
         * then wait until that goal has been reached.
         *
         * 计算到下一个tick还需多长时间，然后sleep，直到时间到达下一个tick
         * 返回 下一个tick达到的时间点
         *
         * @return Long.MIN_VALUE if received a shutdown request,
         * current time otherwise (with Long.MIN_VALUE changed by +1)
         */
        private long waitForNextTick() {
            //计算下一个tick到达的时间点 deadline
            long deadline = tickDuration * (tick + 1);

            for (; ; ) {
                //计算时间轮的当前时间（需要减去时间轮的启动时间）
                final long currentTime = System.nanoTime() - startTime;
                // 计算到达下一个tick还需多长时间 这里需要保证 至少是1毫秒，避免worker线程频繁地sleep
                // tickDuration 的值越小，时间的精准度也就越高，同时 Worker 的繁忙程度越高
                // 如果 tickDuration 设置过小，为了防止系统会频繁地 sleep 再唤醒，需要保证 Worker 至少 sleep 的时间为 1ms 以上。
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;

                if (sleepTimeMs <= 0) {
                    //下一个tick时间点 已经到达 直接返回当前时间点
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }
                if (isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                }

                try {
                    //worker线程睡眠sleepTimeMs 等待时间达到下一个tick
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }
            }
        }

        Set<Timeout> unprocessedTimeouts() {
            return Collections.unmodifiableSet(unprocessedTimeouts);
        }
    }

    /**
     * HashedWheelBucket双向队列中的节点
     * */
    private static final class HashedWheelTimeout implements Timeout {

        private static final int ST_INIT = 0;
        private static final int ST_CANCELLED = 1;
        private static final int ST_EXPIRED = 2;
        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        //执行延时任务的时间轮
        private final HashedWheelTimer timer;
        //延时任务
        private final TimerTask task;
        //延时任务执行时间点
        private final long deadline;

        @SuppressWarnings({"unused", "FieldMayBeFinal", "RedundantFieldInitialization"})
        //延时任务执行状态
        private volatile int state = ST_INIT;

        /**
         * RemainingRounds will be calculated and set by Worker.transferTimeoutsToBuckets() before the
         * HashedWheelTimeout will be added to the correct HashedWheelBucket.
         * 执行该延时任务 需要多少个时钟周期
         */
        long remainingRounds;

        /**
         * This will be used to chain timeouts in HashedWheelTimerBucket via a double-linked-list.
         * As only the workerThread will act on it there is no need for synchronization / volatile.
         */
        HashedWheelTimeout next;
        HashedWheelTimeout prev;

        /**
         * The bucket to which the timeout was added
         * 该延时任务所在的时间槽bucket
         */
        HashedWheelBucket bucket;

        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean cancel() {
            // only update the state it will be removed from HashedWheelBucket on next tick.
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }
            // If a task should be canceled we put this to another queue which will be processed on each tick.
            // So this means that we will have a GC latency of max. 1 tick duration which is good enough. This way
            // we can make again use of our MpscLinkedQueue and so minimize the locking / overhead as much as possible.
            //将取消的延时任务放入 cancelledTimeouts队列中
            timer.cancelledTimeouts.add(this);
            return true;
        }

        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                bucket.remove(this);
            } else {
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        public boolean compareAndSetState(int expected, int state) {
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }

        public int state() {
            return state;
        }

        @Override
        public boolean isCancelled() {
            return state() == ST_CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state() == ST_EXPIRED;
        }

        public void expire() {
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
                return;
            }

            try {
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        @Override
        public String toString() {
            final long currentTime = System.nanoTime();
            long remaining = deadline - currentTime + timer.startTime;
            String simpleClassName = ClassUtils.simpleClassName(this.getClass());

            StringBuilder buf = new StringBuilder(192)
                    .append(simpleClassName)
                    .append('(')
                    .append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining)
                        .append(" ns later");
            } else if (remaining < 0) {
                buf.append(-remaining)
                        .append(" ns ago");
            } else {
                buf.append("now");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(", task: ")
                    .append(task())
                    .append(')')
                    .toString();
        }
    }

    /**
     * Bucket that stores HashedWheelTimeouts. These are stored in a linked-list like datastructure to allow easy
     * removal of HashedWheelTimeouts in the middle. Also the HashedWheelTimeout act as nodes themself and so no
     * extra object creation is needed.
     *
     * 不带头结点的 双向队列 （kafka中为带头结点的双向循环队列）添加删除任务 时间复杂度 O(1)
     */
    private static final class HashedWheelBucket {

        /**
         * Used for the linked-list datastructure
         * 双向队列头尾指针
         */
        private HashedWheelTimeout head;
        private HashedWheelTimeout tail;

        /**
         * Add {@link HashedWheelTimeout} to this bucket.
         */
        void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            timeout.bucket = this;
            if (head == null) {
                head = tail = timeout;
            } else {
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        /**
         * Expire all {@link HashedWheelTimeout}s for the given {@code deadline}.
         * 执行该bucket中 执行时间点 <= 指定deadline的所有延时任务
         */
        void expireTimeouts(long deadline) {
            HashedWheelTimeout timeout = head;

            // process all timeouts
            while (timeout != null) {
                HashedWheelTimeout next = timeout.next;
                if (timeout.remainingRounds <= 0) {
                    next = remove(timeout);
                    if (timeout.deadline <= deadline) {
                        //worker线程执行延时任务
                        timeout.expire();
                    } else {
                        // The timeout was placed into a wrong slot. This should never happen.
                        throw new IllegalStateException(String.format(
                                "timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }
                } else if (timeout.isCancelled()) {
                    next = remove(timeout);
                } else {
                    timeout.remainingRounds--;
                }
                timeout = next;
            }
        }

        public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            HashedWheelTimeout next = timeout.next;
            // remove timeout that was either processed or cancelled by updating the linked-list
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }
            if (timeout.next != null) {
                timeout.next.prev = timeout.prev;
            }

            if (timeout == head) {
                // if timeout is also the tail we need to adjust the entry too
                if (timeout == tail) {
                    tail = null;
                    head = null;
                } else {
                    head = next;
                }
            } else if (timeout == tail) {
                // if the timeout is the tail modify the tail to be the prev node.
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC.
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * Clear this bucket and return all not expired / cancelled {@link Timeout}s.
         */
        void clearTimeouts(Set<Timeout> set) {
            for (; ; ) {
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) {
                    return;
                }
                if (timeout.isExpired() || timeout.isCancelled()) {
                    continue;
                }
                set.add(timeout);
            }
        }

        private HashedWheelTimeout pollTimeout() {
            HashedWheelTimeout head = this.head;
            if (head == null) {
                return null;
            }
            HashedWheelTimeout next = head.next;
            if (next == null) {
                tail = this.head = null;
            } else {
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC.
            head.next = null;
            head.prev = null;
            head.bucket = null;
            return head;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win");
    }
}
