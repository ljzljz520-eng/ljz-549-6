package com.campus.repair;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 真实的工单受理通道：有界队列 + 工作线程模拟下游（工单库 / 派单系统）的受理能力。
 *
 * <p>只有在以下真实不可用场景才拒绝受理（由调用方转为 503 SERVER_BUSY）：
 * <ul>
 *   <li>{@link AcceptResult#REJECTED}：下游处理能力饱和，待受理队列已满（背压）；</li>
 *   <li>{@link AcceptResult#SHUTDOWN}：服务正在关闭，已停止受理新请求。</li>
 * </ul>
 *
 * <p>不再使用“每 N 个请求强制失败一次”的演示计数器：能否受理只取决于实际容量与运行状态。
 * 接生产环境时，将 {@link #dispatch(Ticket)} 替换为真实的落库 / 派单调用即可；
 * 队列满、下游超时等情况仍会如实返回拒绝。
 */
public class TicketAcceptor {

    public enum AcceptResult {
        /** 工单已被下游可靠受理（已进入受理队列）。 */
        ACCEPTED,
        /** 下游处理能力饱和，受理队列已满，请稍后重试。 */
        REJECTED,
        /** 受理通道已关闭（服务停机中）。 */
        SHUTDOWN
    }

    /** 一条已受理的报修工单（值对象）。 */
    public static final class Ticket {
        final String ticketNo;
        final String location;
        final String building;
        final String description;
        final String contact;

        Ticket(String ticketNo, String location, String building,
               String description, String contact) {
            this.ticketNo = ticketNo;
            this.location = location;
            this.building = building;
            this.description = description;
            this.contact = contact;
        }
    }

    private static final int DEFAULT_WORKERS = 2;
    private static final int DEFAULT_QUEUE_CAPACITY = 100;

    private final ThreadPoolExecutor workers;

    public TicketAcceptor() {
        this(DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY);
    }

    public TicketAcceptor(int workerCount, int queueCapacity) {
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(queueCapacity);
        this.workers = new ThreadPoolExecutor(
                workerCount, workerCount,
                0L, TimeUnit.MILLISECONDS,
                queue,
                runnable -> {
                    Thread thread = new Thread(runnable, "ticket-acceptor");
                    thread.setDaemon(true);
                    return thread;
                },
                // 队列满时由提交线程直接感知拒绝（AbortPolicy 抛出 RejectedExecutionException）
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 尝试受理一张工单。仅根据真实容量 / 运行状态决定结果。
     */
    public AcceptResult accept(Ticket ticket) {
        if (workers.isShutdown()) {
            return AcceptResult.SHUTDOWN;
        }
        try {
            workers.execute(() -> dispatch(ticket));
            return AcceptResult.ACCEPTED;
        } catch (RejectedExecutionException e) {
            // 有界队列已满：真实的下游饱和背压，而非人为计数
            return workers.isShutdown() ? AcceptResult.SHUTDOWN : AcceptResult.REJECTED;
        }
    }

    /**
     * 下游处理：实际项目在此落库 / 派单。当前仅占位，处理速度决定队列是否会真实堆积。
     */
    private void dispatch(Ticket ticket) {
        // TODO: 替换为真实的工单落库 / 派单调用；下游异常或超时应向上反馈为受理失败。
    }

    /** 容器卸载时优雅停机：已在队列中的工单尽量处理完。 */
    public void shutdown() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
