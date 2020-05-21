package com.titizz.exercise.distributedlock;

import org.apache.zookeeper.*;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.data.Stat;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;

/**
 * Created by code4wt on 17/8/27.
 */
public class ZKReadWriteLock2 implements ReadWriteLock {

    private static final String LOCK_NODE_PARENT_PATH = "/share_lock";

    /**
     * 自旋测试超时阈值，考虑到网络的延时性，这里设为1000毫秒
     */
    private static final long spinForTimeoutThreshold = 1000L;

    private static final long SLEEP_TIME = 100L;

    private ZooKeeper zooKeeper;

    private CountDownLatch connectedSemaphore = new CountDownLatch(1);

    private ReadLock readLock = new ReadLock();

    private WriteLock writeLock = new WriteLock();

    private Comparator<String> nameComparator;

    public ZKReadWriteLock2() throws Exception {
        Watcher watcher = event -> {
            if (KeeperState.SyncConnected == event.getState()) {
                connectedSemaphore.countDown();
            }
        };
        zooKeeper = new ZooKeeper("127.0.0.1:2181", 1000, watcher);
        connectedSemaphore.await();

        nameComparator = (x, y) -> {
            Integer xs = getSequence(x);
            Integer ys = getSequence(y);
            return xs > ys ? 1 : (xs < ys ? -1 : 0);
        };
    }

    @Override
    public DistributedLock readLock() {
        return readLock;
    }

    @Override
    public DistributedLock writeLock() {
        return writeLock;
    }

    class ReadLock implements DistributedLock, Watcher {

        private LockStatus lockStatus = LockStatus.UNLOCK;

        private CyclicBarrier lockBarrier = new CyclicBarrier(2);

        private String prefix = new Random(System.nanoTime()).nextInt(10000000) + "-read-";

        private String name;

        @Override
        public void lock() throws Exception {
            if (lockStatus == LockStatus.LOCKED) {
                return;
            }

            // 1. 创建锁节点
            if (name == null) {
                name = createLockNode(prefix);
                name = name.substring(name.lastIndexOf("/") + 1);
                System.out.println("创建锁节点 " + name);
            }

            // 2. 获取锁节点列表
            List<String> nodes = zooKeeper.getChildren(LOCK_NODE_PARENT_PATH, this);
            nodes.sort(nameComparator);

            // 找到比自己小的最后一个的写锁节点
            int lastWriteIndex;
            // 3. 检查能否获取锁，若能，直接返回
            if (-1 == (lastWriteIndex = canAcquireLock(name, nodes))) {
                System.out.println(name + " 获取锁");
                lockStatus = LockStatus.LOCKED;
                return;
            }

            // 4. 不能获取锁，监视比自己小的最后一个的写锁节点
            if (lastWriteIndex >= 0) {
                zooKeeper.exists(LOCK_NODE_PARENT_PATH + "/" + nodes.get(lastWriteIndex), this);
            }

            // 5. 等待监视的节点被删除
            lockStatus = LockStatus.TRY_LOCK;
            lockBarrier.await();
        }

        @Override
        public Boolean tryLock() throws Exception {
            if (lockStatus == LockStatus.LOCKED) {
                return true;
            }

            // 1. 创建锁节点
            if (name == null) {
                name = createLockNode(prefix);
                name = name.substring(name.lastIndexOf("/") + 1);
                System.out.println("创建锁节点 " + name);
            }

            // 2. 获取锁节点列表
            List<String> nodes = zooKeeper.getChildren(LOCK_NODE_PARENT_PATH, null);
            nodes.sort(nameComparator);

            // 3. 检查能否获取锁
            if (canAcquireLock(name, nodes) == -1) {
                System.out.println(name + " 获取锁");
                lockStatus = LockStatus.LOCKED;
                return true;
            }

            return false;
        }

        @Override
        public Boolean tryLock(long millisecond) throws Exception {
            long millisTimeout = millisecond;
            if (millisTimeout <= 0L) {
                return false;
            }

            final long deadline = System.currentTimeMillis() + millisTimeout;
            for (; ; ) {
                if (tryLock()) {
                    return true;
                }

                if (millisTimeout > spinForTimeoutThreshold) {
                    Thread.sleep(SLEEP_TIME);
                }

                millisTimeout = deadline - System.currentTimeMillis();
                if (millisTimeout <= 0L) {
                    return false;
                }
            }
        }

        @Override
        public void unlock() throws Exception {
            if (lockStatus == LockStatus.UNLOCK) {
                return;
            }

            deleteLockNode(name);
            lockStatus = LockStatus.UNLOCK;
            lockBarrier.reset();
            System.out.println(name + " 释放锁");
            name = null;
        }

        @Override
        public void process(WatchedEvent event) {
            if (KeeperState.SyncConnected != event.getState()) {
                return;
            }
            if (EventType.None == event.getType() && event.getPath() == null) {
                connectedSemaphore.countDown();
            } else if (EventType.NodeDeleted == event.getType()) {
                if (lockStatus != LockStatus.TRY_LOCK) {
                    return;
                }

                System.out.println(name + " 获取锁");
                lockStatus = LockStatus.LOCKED;
                try {
                    lockBarrier.await();
                } catch (InterruptedException | BrokenBarrierException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    class WriteLock implements DistributedLock, Watcher {

        private LockStatus lockStatus = LockStatus.UNLOCK;

        private CyclicBarrier lockBarrier = new CyclicBarrier(2);

        private String prefix = new Random(System.nanoTime()).nextInt(1000000) + "-write-";

        private String name;

        @Override
        public void lock() throws Exception {
            if (lockStatus == LockStatus.LOCKED) {
                return;
            }

            // 1. 创建锁节点
            if (name == null) {
                name = createLockNode(prefix);
                name = name.substring(name.lastIndexOf("/") + 1);
                System.out.println("创建锁节点 " + name);
            }

            // 2. 获取锁节点列表
            List<String> nodes = zooKeeper.getChildren(LOCK_NODE_PARENT_PATH, null);
            nodes.sort(nameComparator);

            // 3. 检查自己是否是排在第一位，若是，加锁成功
            if (isFirstNode(name, nodes)) {
                System.out.println(name + " 获取锁");
                lockStatus = LockStatus.LOCKED;
                return;
            }

            // 4. 若不是，定位到上一个锁节点，并监视
            int index = Collections.binarySearch(nodes, name, nameComparator);
            zooKeeper.exists(LOCK_NODE_PARENT_PATH + "/" + nodes.get(index - 1), this);

            // 5. 等待监视的节点被删除
            lockStatus = LockStatus.TRY_LOCK;
            lockBarrier.await();
        }

        @Override
        public Boolean tryLock() throws Exception {
            if (lockStatus == LockStatus.LOCKED) {
                return true;
            }

            // 1. 创建锁节点
            if (name == null) {
                name = createLockNode(prefix);
                name = name.substring(name.lastIndexOf("/") + 1);
                System.out.println("创建锁节点 " + name);
            }

            // 2. 获取锁节点列表
            List<String> nodes = zooKeeper.getChildren(LOCK_NODE_PARENT_PATH, null);
            nodes.sort(nameComparator);

            // 3. 检查自己是否是排在第一位，若是，加锁成功
            if (isFirstNode(name, nodes)) {
                System.out.println(name + " 获取锁");
                lockStatus = LockStatus.LOCKED;
                return true;
            }

            return false;
        }

        @Override
        public Boolean tryLock(long millisecond) throws Exception {
            long millisTimeout = millisecond;
            if (millisTimeout <= 0L) {
                return false;
            }

            final long deadline = System.currentTimeMillis() + millisTimeout;
            for (; ; ) {
                if (tryLock()) {
                    return true;
                }

                if (millisTimeout > spinForTimeoutThreshold) {
                    Thread.sleep(SLEEP_TIME);
                }

                millisTimeout = deadline - System.currentTimeMillis();
                if (millisTimeout <= 0L) {
                    return false;
                }
            }
        }

        @Override
        public void unlock() throws Exception {
            if (lockStatus == LockStatus.UNLOCK) {
                return;
            }

            System.out.println(name + " 释放锁");
            deleteLockNode(name);
            lockStatus = LockStatus.UNLOCK;
            lockBarrier.reset();
            name = null;
        }

        @Override
        public void process(WatchedEvent event) {
            if (KeeperState.SyncConnected != event.getState()) {
                return;
            }

            if (EventType.None == event.getType() && event.getPath() == null) {
                connectedSemaphore.countDown();
            } else if (EventType.NodeDeleted == event.getType()) {
                if (lockStatus != LockStatus.TRY_LOCK) {
                    return;
                }
                try {
                    List<String> nodes = zooKeeper.getChildren(LOCK_NODE_PARENT_PATH, null);
                    nodes.sort(nameComparator);
                    if (!isFirstNode(name, nodes)) {
                        int index = Collections.binarySearch(nodes, name, nameComparator);
                        zooKeeper.exists(LOCK_NODE_PARENT_PATH + "/" + nodes.get(index - 1), this);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                lockStatus = LockStatus.LOCKED;
                try {
                    lockBarrier.await();
                    System.out.println(name + " 获取锁");
                } catch (InterruptedException | BrokenBarrierException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private Integer getSequence(String name) {
        return Integer.valueOf(name.substring(name.lastIndexOf("-") + 1));
    }

    private String createLockNode(String name) {
        String path = null;
        try {
            path = zooKeeper.create(LOCK_NODE_PARENT_PATH + "/" + name, "".getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
        } catch (KeeperException | InterruptedException e) {
            e.printStackTrace();
            return null;
        }
        return path;
    }

    private void deleteLockNode(String name) throws KeeperException, InterruptedException {
        Stat stat = zooKeeper.exists(LOCK_NODE_PARENT_PATH + "/" + name, false);
        zooKeeper.delete(LOCK_NODE_PARENT_PATH + "/" + name, stat.getVersion());
    }

//    private Boolean canAcquireLock(String name, List<String> nodes) {
//        if (isFirstNode(name, nodes)) {
//            return true;
//        }
//
//        Map<String, Boolean> map = new HashMap<>();
//        boolean hasWriteoperation = false;
//        for (String n : nodes) {
//            if (n.contains("read") && !hasWriteoperation) {
//                map.put(n, true);
//            } else {
//                hasWriteoperation = true;
//                map.put((n), false);
//            }
//        }
//
//        return map.get(name);
//    }

    private int canAcquireLock(String name, List<String> nodes) {
        if (isFirstNode(name, nodes)) {
            return -1;
        }
        // 获取当前锁的角标
        int index = Collections.binarySearch(nodes, name, nameComparator);
        for (int i = index - 1; i >= 0; i--) { // 遍历当前node之前的节点
            if (nodes.get(i).contains("write")) { // 如果存在write锁则等待锁
                return i; // 返回最后write锁角标
            }
        }
        return -1;
    }

    private Boolean isFirstNode(String name, List<String> nodes) {
        return nodes.get(0).equals(name);
    }

}
