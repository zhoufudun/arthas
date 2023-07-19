package com.taobao.arthas.core.command.monitor200;

import com.taobao.arthas.core.advisor.Advice;
import com.taobao.arthas.core.advisor.ArthasMethod;
import com.taobao.arthas.core.advisor.ReflectAdviceListenerAdapter;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.DateUtils;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.StringUtils;
import com.taobao.arthas.core.util.ThreadLocalWatch;
import com.taobao.arthas.core.view.ObjectView;
import com.taobao.middleware.logger.Logger;

/**
 * @author beiwei30 on 29/11/2016.
 */
class WatchAdviceListener extends ReflectAdviceListenerAdapter {

    private static final Logger logger = LogUtil.getArthasLogger();
    private final ThreadLocalWatch threadLocalWatch = new ThreadLocalWatch();
    private WatchCommand command;
    private CommandProcess process;

    public WatchAdviceListener(WatchCommand command, CommandProcess process) {
        this.command = command;
        this.process = process;
    }

    private boolean isFinish() {
        return command.isFinish() || !command.isBefore() && !command.isException() && !command.isSuccess();
    }

    @Override
    public void before(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args)
            throws Throwable {
        // 开始计算本次方法调用耗时
        threadLocalWatch.start();
        if (command.isBefore()) {
            watching(Advice.newForBefore(loader, clazz, method, target, args));
        }
    }

    @Override
    public void afterReturning(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args,
                               Object returnObject) throws Throwable {
        Advice advice = Advice.newForAfterRetuning(loader, clazz, method, target, args, returnObject);
        if (command.isSuccess()) {
            watching(advice);
        }

        finishing(advice);
    }
    // 真实方法抛异常后的处理
    @Override
    public void afterThrowing(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args,
                              Throwable throwable) {
        Advice advice = Advice.newForAfterThrowing(loader, clazz, method, target, args, throwable);
        if (command.isException()) {
            watching(advice);
        }

        finishing(advice);
    }

    private void finishing(Advice advice) {
        if (isFinish()) {
            watching(advice);
        }
    }

    private boolean isNeedExpand() {
        Integer expand = command.getExpand(); // 执行结果的对象是否展开,>=0表示需要展开
        return null != expand && expand >= 0;
    }

    private void watching(Advice advice) { // 方法调用结束，执行耗时统计
        try {
            // 本次调用的耗时
            double cost = threadLocalWatch.costInMillis();
            if (isConditionMet(command.getConditionExpress(), advice, cost)) {
                // TODO: concurrency issues for process.write
                Object value = getExpressionResult(command.getExpress(), advice, cost); //express:{params,returnObj,throwExp}
                String result = StringUtils.objectToString(
                        isNeedExpand() ? new ObjectView(value, command.getExpand(), command.getSizeLimit()).draw() : value);
                process.write("ts=" + DateUtils.getCurrentDate() + "; [cost=" + cost + "ms] result=" + result + "\n");
                /**
                 * 举例：ts=2023-07-19 15:00:21; [cost=0.3152ms] result=@ArrayList[
                 *      @Object[][
                 *          @Integer[143440],
                 *          @ArrayList[
                 *                 @Integer[2],
                 *                 @Integer[2],
                 *                 @Integer[2],
                 *                 @Integer[2],
                 *                 @Integer[5],
                 *                 @Integer[11],
                 *                 @Integer[163],
                 *         ],
                 *     ],
                 *     null,
                 *     null,
                 * ]
                 */

                process.times().incrementAndGet(); // 本次执行完毕，已经执行次数+1
                if (isLimitExceeded(command.getNumberOfLimit(), process.times().get())) {
                    abortProcess(process, command.getNumberOfLimit());
                }
            }
        } catch (Exception e) {
            logger.warn("watch failed.", e);
            process.write("watch failed, condition is: " + command.getConditionExpress() + ", express is: "
                          + command.getExpress() + ", " + e.getMessage() + ", visit " + LogUtil.LOGGER_FILE
                          + " for more details.\n");
            process.end();
        }
    }
}
