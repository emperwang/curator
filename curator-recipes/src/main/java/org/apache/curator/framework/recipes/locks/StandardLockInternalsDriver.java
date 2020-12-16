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

import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

public class StandardLockInternalsDriver implements LockInternalsDriver
{
    static private final Logger log = LoggerFactory.getLogger(StandardLockInternalsDriver.class);
    // 获取锁
    @Override
    public PredicateResults getsTheLock(CuratorFramework client, List<String> children, String sequenceNodeName, int maxLeases) throws Exception
    {
        // 判断要获取的序列节点在 排序后数组的位置
        int             ourIndex = children.indexOf(sequenceNodeName);
        validateOurIndex(sequenceNodeName, ourIndex);
        // 如果是小于 macLeases,则说明获取到锁
        boolean         getsTheLock = ourIndex < maxLeases;
        // 如果不是小于 maxLeases,则获取其前一个获取到锁的 节点
        String          pathToWatch = getsTheLock ? null : children.get(ourIndex - maxLeases);
        // 返回结果,pathToWatch:要监听的节点  getsTheLock:是否获取到锁
        return new PredicateResults(pathToWatch, getsTheLock);
    }
    // 创建锁,则创建一个 EPHEMERAL_SEQUENTIAL类型的序列 节点
    @Override
    public String createsTheLock(CuratorFramework client, String path, byte[] lockNodeBytes) throws Exception
    {
        String ourPath;
        if ( lockNodeBytes != null )
        {
            // 有数据的创建节点
            ourPath = client.create().creatingParentContainersIfNeeded().withProtection().withMode(CreateMode.EPHEMERAL_SEQUENTIAL).forPath(path, lockNodeBytes);
        }
        else
        {
            // 没有数据的节点创建
            ourPath = client.create().creatingParentContainersIfNeeded().withProtection().withMode(CreateMode.EPHEMERAL_SEQUENTIAL).forPath(path);
        }
        return ourPath;
    }


    @Override
    public String fixForSorting(String str, String lockName)
    {
        return standardFixForSorting(str, lockName);
    }

    public static String standardFixForSorting(String str, String lockName)
    {
        int index = str.lastIndexOf(lockName);
        if ( index >= 0 )
        {
            index += lockName.length();
            return index <= str.length() ? str.substring(index) : "";
        }
        return str;
    }

    static void validateOurIndex(String sequenceNodeName, int ourIndex) throws KeeperException
    {
        if ( ourIndex < 0 )
        {
            throw new KeeperException.NoNodeException("Sequential path not found: " + sequenceNodeName);
        }
    }
}
