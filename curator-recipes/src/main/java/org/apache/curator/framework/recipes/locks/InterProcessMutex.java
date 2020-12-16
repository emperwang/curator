/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.curator.framework.recipes.locks;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.curator.framework.CuratorFramework;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.curator.utils.PathUtils;

/**
 * A re-entrant mutex that works across JVMs. Uses Zookeeper to hold the lock. All processes in all JVMs that
 * use the same lock path will achieve an inter-process critical section. Further, this mutex is
 * "fair" - each user will get the mutex in the order requested (from ZK's point of view)
 */
public class InterProcessMutex implements InterProcessLock, Revocable<InterProcessMutex>
{
    private final LockInternals internals;
    private final String basePath;
    // 存储每个线程对应的 索引信息
    private final ConcurrentMap<Thread, LockData> threadData = Maps.newConcurrentMap();
    /*
        内部类,存储对应的获取锁的信息
     */
    private static class LockData
    {
        // 线程
        final Thread owningThread;
        // 占用的线程
        final String lockPath;
        // 重入次数
        final AtomicInteger lockCount = new AtomicInteger(1);

        private LockData(Thread owningThread, String lockPath)
        {
            this.owningThread = owningThread;
            this.lockPath = lockPath;
        }
    }
    // lock锁的前缀
    private static final String LOCK_NAME = "lock-";

    /**
     * @param client client
     * @param path   the path to lock
     */
    // 构造函数
    public InterProcessMutex(CuratorFramework client, String path)
    {
        this(client, path, new StandardLockInternalsDriver());
    }

    /**
     * @param client client
     * @param path   the path to lock
     * @param driver lock driver
     */
    // 初始化
    public InterProcessMutex(CuratorFramework client, String path, LockInternalsDriver driver)
    {
        this(client, path, LOCK_NAME, 1, driver);
    }

    /**
     * Acquire the mutex - blocking until it's available. Note: the same thread
     * can call acquire re-entrantly. Each call to acquire must be balanced by a call
     * to {@link #release()}
     *
     * @throws Exception ZK errors, connection interruptions
     */
    @Override
    public void acquire() throws Exception
    {
        // 获取锁
        // 没有获取到锁,则抛出错误
        if ( !internalLock(-1, null) )
        {
            throw new IOException("Lost connection while trying to acquire lock: " + basePath);
        }
    }

    /**
     * Acquire the mutex - blocks until it's available or the given time expires. Note: the same thread
     * can call acquire re-entrantly. Each call to acquire that returns true must be balanced by a call
     * to {@link #release()}
     *
     * @param time time to wait
     * @param unit time unit
     * @return true if the mutex was acquired, false if not
     * @throws Exception ZK errors, connection interruptions
     */
    @Override
    public boolean acquire(long time, TimeUnit unit) throws Exception
    {
        return internalLock(time, unit);
    }

    /**
     * Returns true if the mutex is acquired by a thread in this JVM
     *
     * @return true/false
     */
    // 判断一个锁是否被 一个线程占用
    @Override
    public boolean isAcquiredInThisProcess()
    {
        return (threadData.size() > 0);
    }

    /**
     * Perform one release of the mutex if the calling thread is the same thread that acquired it. If the
     * thread had made multiple calls to acquire, the mutex will still be held when this method returns.
     *
     * @throws Exception ZK errors, interruptions, current thread does not own the lock
     */
    // 释放锁的操作
    @Override
    public void release() throws Exception
    {
        /*
            Note on concurrency: a given lockData instance
            can be only acted on by a single thread so locking isn't necessary
         */
        // 获取当前线程对应的锁信息
        Thread currentThread = Thread.currentThread();
        LockData lockData = threadData.get(currentThread);
        if ( lockData == null )
        {
            throw new IllegalMonitorStateException("You do not own the lock: " + basePath);
        }
        // 进行锁重入的释放
        int newLockCount = lockData.lockCount.decrementAndGet();
        // 重入次数还大于0,说明还占有锁
        if ( newLockCount > 0 )
        {
            return;
        }
        if ( newLockCount < 0 )
        {
            throw new IllegalMonitorStateException("Lock count has gone negative for lock: " + basePath);
        }
        try
        {
            // 释放锁
            internals.releaseLock(lockData.lockPath);
        }
        finally
        {
            // 移除对应的锁信息
            threadData.remove(currentThread);
        }
    }

    /**
     * Return a sorted list of all current nodes participating in the lock
     *
     * @return list of nodes
     * @throws Exception ZK errors, interruptions, etc.
     */
    // 获取争用此锁的 内部路径
    public Collection<String> getParticipantNodes() throws Exception
    {
        return LockInternals.getParticipantNodes(internals.getClient(), basePath, internals.getLockName(), internals.getDriver());
    }

    @Override
    public void makeRevocable(RevocationListener<InterProcessMutex> listener)
    {
        makeRevocable(listener, MoreExecutors.sameThreadExecutor());
    }

    @Override
    public void makeRevocable(final RevocationListener<InterProcessMutex> listener, Executor executor)
    {
        internals.makeRevocable(new RevocationSpec(executor, new Runnable()
            {
                @Override
                public void run()
                {
                    listener.revocationRequested(InterProcessMutex.this);
                }
            }));
    }

    InterProcessMutex(CuratorFramework client, String path, String lockName, int maxLeases, LockInternalsDriver driver)
    {
        // 路径的验证
        basePath = PathUtils.validatePath(path);
        // LockInternals 真正对锁的操作
        internals = new LockInternals(client, driver, path, lockName, maxLeases);
    }
    // 判断此锁是否被当前线程占用
    boolean isOwnedByCurrentThread()
    {
        // 获取当前线程的锁信息
        LockData lockData = threadData.get(Thread.currentThread());
        // 根据所信息类判断是否占用
        return (lockData != null) && (lockData.lockCount.get() > 0);
    }

    protected byte[] getLockNodeBytes()
    {
        return null;
    }

    protected String getLockPath()
    {
        // 获取当前锁的 占用path
        LockData lockData = threadData.get(Thread.currentThread());
        return lockData != null ? lockData.lockPath : null;
    }
    // 获取锁操作
    private boolean internalLock(long time, TimeUnit unit) throws Exception
    {
        /*
           Note on concurrency: a given lockData instance
           can be only acted on by a single thread so locking isn't necessary
        */
        // 当前线程
        Thread currentThread = Thread.currentThread();
        // 当前线程对应的锁信息
        LockData lockData = threadData.get(currentThread);
        // 如果有当前线程的锁信息,说明获取过锁,则直接重入
        if ( lockData != null )
        {
            // re-entering
            lockData.lockCount.incrementAndGet();
            return true;
        }
        // 没有获取过锁,则尝试获取锁
        // 如果获取到锁,则返回自己的path,否则返回null
        String lockPath = internals.attemptLock(time, unit, getLockNodeBytes());
        // lockPath不为null,则保存获取到的锁信息
        if ( lockPath != null )
        {
            // 创建LockData 保存对应的锁信息
            LockData newLockData = new LockData(currentThread, lockPath);
            threadData.put(currentThread, newLockData);
            return true;
        }

        return false;
    }
}
