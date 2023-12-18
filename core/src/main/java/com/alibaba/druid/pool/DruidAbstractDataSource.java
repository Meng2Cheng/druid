/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.pool;

import com.alibaba.druid.DbType;
import com.alibaba.druid.DruidRuntimeException;
import com.alibaba.druid.VERSION;
import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.filter.FilterChainImpl;
import com.alibaba.druid.filter.FilterManager;
import com.alibaba.druid.pool.vendor.NullExceptionSorter;
import com.alibaba.druid.proxy.jdbc.ConnectionProxyImpl;
import com.alibaba.druid.proxy.jdbc.DataSourceProxy;
import com.alibaba.druid.proxy.jdbc.TransactionInfo;
import com.alibaba.druid.stat.JdbcDataSourceStat;
import com.alibaba.druid.stat.JdbcSqlStat;
import com.alibaba.druid.stat.JdbcStatManager;
import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;
import com.alibaba.druid.util.*;

import javax.management.JMException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeDataSupport;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.sql.DataSource;

import java.io.PrintWriter;
import java.io.Serializable;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import static com.alibaba.druid.util.JdbcConstants.POSTGRESQL_DRIVER;

/**
 * @author wenshao [szujobs@hotmail.com]
 * @author ljw [ljw2083@alibaba-inc.com]
 */
public abstract class DruidAbstractDataSource extends WrapperAdapter implements DruidAbstractDataSourceMBean, DataSource, DataSourceProxy, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Log LOG = LogFactory.getLog(DruidAbstractDataSource.class);

    public static final int DEFAULT_INITIAL_SIZE = 0; // 初始连接数，默认为0
    public static final int DEFAULT_MAX_ACTIVE_SIZE = 8; // 最大活跃连接数默认值
    public static final int DEFAULT_MAX_IDLE = 8; // 默认最大连接数
    public static final int DEFAULT_MIN_IDLE = 0; // 最小连接数默认值
    public static final int DEFAULT_MAX_WAIT = -1; // 默认最大等待时间
    public static final String DEFAULT_VALIDATION_QUERY = null; // 默认验证sql
    public static final boolean DEFAULT_TEST_ON_BORROW = false; // 获取连接时检测
    public static final boolean DEFAULT_TEST_ON_RETURN = false; // 连接放回连接池时检测
    public static final boolean DEFAULT_WHILE_IDLE = true; // 空闲时检测
    public static final long DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS = 60 * 1000L; // 检查空闲连接的频率，默认值为1分钟
    public static final long DEFAULT_TIME_BETWEEN_CONNECT_ERROR_MILLIS = 500; // 连接出错后重试时间间隔，默认 0.5秒
    public static final int DEFAULT_NUM_TESTS_PER_EVICTION_RUN = 3; // 每次驱逐运行的测试数 todo
    public static final int DEFAULT_TIME_CONNECT_TIMEOUT_MILLIS = 10_000; // 连接超时时间默认值
    public static final int DEFAULT_TIME_SOCKET_TIMEOUT_MILLIS = 10_000; // TCP连接超时时间默认值

    public static final long DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS = 1000L * 60L * 30L; // 最小可驱逐空闲时间毫秒
    public static final long DEFAULT_MAX_EVICTABLE_IDLE_TIME_MILLIS = 1000L * 60L * 60L * 7; // 最大可驱逐空闲时间毫秒
    public static final long DEFAULT_PHY_TIMEOUT_MILLIS = -1; // 物理超时毫秒

    protected volatile boolean defaultAutoCommit = true; // 默认autocommit设置
    protected volatile Boolean defaultReadOnly; // 默认只读设置
    protected volatile Integer defaultTransactionIsolation; // 默认事务隔离
    protected volatile String defaultCatalog; // 默认目录

    protected String name;

    protected volatile String username; // 数据库用户名
    protected volatile String password; // 数据库密码
    protected volatile String jdbcUrl; // 数据库url
    protected volatile String driverClass; // 数据库驱动
    protected volatile ClassLoader driverClassLoader; // 数据库驱动类加载器
    protected volatile Properties connectProperties = new Properties(); // 连接配置

    protected volatile PasswordCallback passwordCallback; // 密码回调（例如加密）
    protected volatile NameCallback userCallback; // 用户名回调（例如加密）

    protected volatile int initialSize = DEFAULT_INITIAL_SIZE; // 初始连接数，默认为0
    protected volatile int maxActive = DEFAULT_MAX_ACTIVE_SIZE; // 最大活跃连接数，默认为8
    protected volatile int minIdle = DEFAULT_MIN_IDLE; // 最小连接数，默认为0
    protected volatile int maxIdle = DEFAULT_MAX_IDLE; // 最大连接数
    protected volatile long maxWait = DEFAULT_MAX_WAIT; // 最大等待时间
    protected int notFullTimeoutRetryCount; // 获取连接最大重试次数

    protected volatile String validationQuery = DEFAULT_VALIDATION_QUERY; // 验证sql
    protected volatile int validationQueryTimeout = -1; // 验证sql超时时间
    protected volatile boolean testOnBorrow = DEFAULT_TEST_ON_BORROW; // 获取连接时检测
    protected volatile boolean testOnReturn = DEFAULT_TEST_ON_RETURN; // 连接放回连接池时检测
    protected volatile boolean testWhileIdle = DEFAULT_WHILE_IDLE; // 空闲时检测
    protected volatile boolean poolPreparedStatements;
    protected volatile boolean sharePreparedStatements;
    protected volatile int maxPoolPreparedStatementPerConnectionSize = 10;

    protected volatile boolean inited; // 连接池是否已经初始化
    protected volatile boolean initExceptionThrow = true;

    protected PrintWriter logWriter = new PrintWriter(System.out);

    protected List<Filter> filters = new CopyOnWriteArrayList<Filter>(); // 过滤器集合
    private boolean clearFiltersEnable = true;
    protected volatile ExceptionSorter exceptionSorter; // ExceptionSorter类名

    protected Driver driver; // 数据库驱动

    protected volatile int connectTimeout; // 连接超时时间
    protected volatile int socketTimeout; // Socket超时时间
    private volatile String connectTimeoutStr;
    private volatile String socketTimeoutSr;

    protected volatile int queryTimeout; // 查询超时时间 seconds
    protected volatile int transactionQueryTimeout; // 事务查询超时时间 seconds

    // 创建物理连接总耗时
    protected long createTimespan;

    protected volatile int maxWaitThreadCount = -1; // 最大等待线程数
    protected volatile boolean accessToUnderlyingConnectionAllowed = true; // 是否允许访问底层连接

    protected volatile long timeBetweenEvictionRunsMillis = DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS; // 检查空闲连接的频率，默认值为1分钟
    protected volatile int numTestsPerEvictionRun = DEFAULT_NUM_TESTS_PER_EVICTION_RUN; // 每次驱逐运行的测试数 todo
    protected volatile long minEvictableIdleTimeMillis = DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS; // 最小可驱逐空闲时间毫秒
    protected volatile long maxEvictableIdleTimeMillis = DEFAULT_MAX_EVICTABLE_IDLE_TIME_MILLIS; // 最大可驱逐空闲时间毫秒
    protected volatile long keepAliveBetweenTimeMillis = DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS * 2; // 保活时间间隔
    protected volatile long phyTimeoutMillis = DEFAULT_PHY_TIMEOUT_MILLIS; // 物理超时毫秒
    protected volatile long phyMaxUseCount = -1; // 连接设置的最大使用次数（超过次数要回收连接）

    protected volatile boolean removeAbandoned; // 是否要移除疑似泄露的连接
    protected volatile long removeAbandonedTimeoutMillis = 300 * 1000; // 超时时间（一个连接超过该时间，且不运行，则可能存在连接泄露，需要自动回收）
    protected volatile boolean logAbandoned; // 是否丢弃日志

    protected volatile int maxOpenPreparedStatements = -1;

    protected volatile List<String> connectionInitSqls;

    protected volatile String dbTypeName;

    protected volatile long timeBetweenConnectErrorMillis = DEFAULT_TIME_BETWEEN_CONNECT_ERROR_MILLIS; // 连接出错后重试时间间隔，默认 0.5秒

    protected volatile ValidConnectionChecker validConnectionChecker; // 连接有效性检查类名

    protected volatile boolean usePingMethod;

    protected final Map<DruidPooledConnection, Object> activeConnections = new IdentityHashMap<DruidPooledConnection, Object>(); // 活跃连接
    protected static final Object PRESENT = new Object();

    protected long id; // 数据源ID

    protected int connectionErrorRetryAttempts = 1; // 连接错误重试次数
    protected boolean breakAfterAcquireFailure; // 失败后是否需要中断
    protected long transactionThresholdMillis;

    protected final java.util.Date createdTime = new java.util.Date();
    protected java.util.Date initedTime; // 初始化完成时间
    protected volatile long errorCount; // 错误数
    protected volatile long dupCloseCount;
    protected volatile long startTransactionCount;
    protected volatile long commitCount;    // 提交数
    protected volatile long rollbackCount;     // 回滚数
    protected volatile long cachedPreparedStatementHitCount; // PSCache缓存命中次数
    protected volatile long preparedStatementCount; // PSCache数量
    protected volatile long closedPreparedStatementCount; // PSCache关闭数
    protected volatile long cachedPreparedStatementCount; // PSCache缓存数量
    protected volatile long cachedPreparedStatementDeleteCount; // PSCache缓存删除数
    protected volatile long cachedPreparedStatementMissCount; // PSCache缓存不命中次数

    private volatile FilterChainImpl filterChain;

    // 错误数
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> errorCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "errorCount");
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> dupCloseCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "dupCloseCount");
    // 事务启动数
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> startTransactionCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "startTransactionCount");
    // 提交数
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> commitCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "commitCount");
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> rollbackCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "rollbackCount");
    // PSCache命中次数
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> cachedPreparedStatementHitCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "cachedPreparedStatementHitCount");
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> preparedStatementCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "preparedStatementCount");
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> closedPreparedStatementCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "closedPreparedStatementCount");
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> cachedPreparedStatementCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "cachedPreparedStatementCount");
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> cachedPreparedStatementDeleteCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "cachedPreparedStatementDeleteCount");
    // PSCache不命中次数
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> cachedPreparedStatementMissCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "cachedPreparedStatementMissCount");
    protected static final AtomicReferenceFieldUpdater<DruidAbstractDataSource, FilterChainImpl> filterChainUpdater
            = AtomicReferenceFieldUpdater.newUpdater(DruidAbstractDataSource.class, FilterChainImpl.class, "filterChain");

    // 事务时间分布
    protected final Histogram transactionHistogram = new Histogram(1,
            10,
            100,
            1000,
            10 * 1000,
            100 * 1000);

    private boolean dupCloseLogEnable;

    private ObjectName objectName;

    protected volatile long executeCount; // sql执行数
    protected volatile long executeQueryCount; // 统计sql执行次数
    protected volatile long executeUpdateCount; // 更新sql执行次数
    protected volatile long executeBatchCount; // 批处理sql执行次数

    /**
     * sql统计次数线程安全类
     */
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> executeQueryCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "executeQueryCount");
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> executeUpdateCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "executeUpdateCount");
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> executeBatchCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "executeBatchCount");
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> executeCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "executeCount");

    protected volatile Throwable createError; // 创建连接错误信息
    protected volatile Throwable lastError; // 最近一次错误信息
    protected volatile long lastErrorTimeMillis; // 最近一次错误时间
    protected volatile Throwable lastCreateError; //  最近一次创建连接错误信息
    protected volatile long lastCreateErrorTimeMillis; // 最近一次创建连接错误时间
    protected volatile long lastCreateStartTimeMillis;

    // 数据库类型
    protected boolean isOracle;
    protected boolean isMySql;
    protected boolean useOracleImplicitCache = true;

    protected ReentrantLock lock; // 可重入锁，用户可创建、可回收的加锁处理
    protected Condition notEmpty; // 连接池没有可用连接时，应用线程会在notEmpty上等待，
    protected Condition empty; // 连接池已满时，生产连接的线程会在empty上等待

    protected ReentrantLock activeConnectionLock = new ReentrantLock(); // 活跃连接锁

    protected volatile int createErrorCount; // 物理连接错误次数
    protected volatile int creatingCount;  // 创建物理连接数量统计
    protected volatile int directCreateCount; // 直接创建数量
    protected volatile long createCount; // 物理连接打开次数
    protected volatile long destroyCount;  // 物理关闭数量
    protected volatile long createStartNanos; // 创建连接开始时间

    // 物理连接错误次数
    static final AtomicIntegerFieldUpdater<DruidAbstractDataSource> createErrorCountUpdater = AtomicIntegerFieldUpdater.newUpdater(DruidAbstractDataSource.class, "createErrorCount");
    // 创建物理连接数量统计
    static final AtomicIntegerFieldUpdater<DruidAbstractDataSource> creatingCountUpdater = AtomicIntegerFieldUpdater.newUpdater(DruidAbstractDataSource.class, "creatingCount");
    // 直接创建数量
    static final AtomicIntegerFieldUpdater<DruidAbstractDataSource> directCreateCountUpdater = AtomicIntegerFieldUpdater.newUpdater(DruidAbstractDataSource.class, "directCreateCount");
    // 物理连接打开次数
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> createCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "createCount");
    // 物理关闭数量
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> destroyCountUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "destroyCount"); // 线程安全的更新销毁连接数destroyCount
    // 创建连接开始时间
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> createStartNanosUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "createStartNanos");

    private Boolean useUnfairLock; // 是否使用公平锁
    private boolean useLocalSessionState = true; // 是否使用本地会话状态

    protected long timeBetweenLogStatsMillis; // 打印线程池配置信息时间间隔
    protected DruidDataSourceStatLogger statLogger = new DruidDataSourceStatLoggerImpl();

    protected boolean asyncCloseConnectionEnable; // 是否异步关闭连接
    protected int maxCreateTaskCount = 3; // 最大创建任务数
    protected boolean failFast; // 是否快速失败
    protected volatile int failContinuous; // 是否连续失败标识
    protected volatile long failContinuousTimeMillis; // 连续失败时间
    protected ScheduledExecutorService destroyScheduler; // 销毁连接定时任务线程池
    protected ScheduledExecutorService createScheduler; // 创建连接定时任务线程池
    protected Executor netTimeoutExecutor; // 网络超时线程池
    protected volatile boolean netTimeoutError; // 是否网络超时错误

    // 连续失败时间，失败为当前时间，成功为0
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> failContinuousTimeMillisUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "failContinuousTimeMillis");
    // 连续失败标志，失败为1，成功为0
    static final AtomicIntegerFieldUpdater<DruidAbstractDataSource> failContinuousUpdater = AtomicIntegerFieldUpdater.newUpdater(DruidAbstractDataSource.class, "failContinuous");

    protected boolean initVariants; // 使用初始化变量
    protected boolean initGlobalVariants; // 是否初始化全局变量
    protected volatile boolean onFatalError; // 是否致命错误
    protected volatile int onFatalErrorMaxActive; // todo:致命错误的最大活跃数？
    protected volatile int fatalErrorCount; // 致命错误计数
    protected volatile int fatalErrorCountLastShrink; // 致命错误计数最后一次收缩
    protected volatile long lastFatalErrorTimeMillis; // 最近一次发生错误的时间
    protected volatile String lastFatalErrorSql; // 最近一次致命错误的sql
    protected volatile Throwable lastFatalError; // 最近一次致命错误
    protected volatile Throwable keepAliveError;

    public DruidAbstractDataSource(boolean lockFair) {
        lock = new ReentrantLock(lockFair);
        notEmpty = lock.newCondition();
        empty = lock.newCondition();
    }

    protected FilterChainImpl createChain() {
        FilterChainImpl chain = filterChainUpdater.getAndSet(this, null);
        if (chain == null) {
            chain = new FilterChainImpl(this);
        }
        return chain;
    }

    protected void recycleFilterChain(FilterChainImpl chain) {
        chain.reset();
        filterChainUpdater.lazySet(this, chain);
    }

    public boolean isUseLocalSessionState() {
        return useLocalSessionState;
    }

    public void setUseLocalSessionState(boolean useLocalSessionState) {
        this.useLocalSessionState = useLocalSessionState;
    }

    public DruidDataSourceStatLogger getStatLogger() {
        return statLogger;
    }

    public void setStatLoggerClassName(String className) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className);
            DruidDataSourceStatLogger statLogger = (DruidDataSourceStatLogger) clazz.newInstance();
            this.setStatLogger(statLogger);
        } catch (Exception e) {
            throw new IllegalArgumentException(className, e);
        }
    }

    public void setStatLogger(DruidDataSourceStatLogger statLogger) {
        this.statLogger = statLogger;
    }

    public long getTimeBetweenLogStatsMillis() {
        return timeBetweenLogStatsMillis;
    }

    public void setTimeBetweenLogStatsMillis(long timeBetweenLogStatsMillis) {
        this.timeBetweenLogStatsMillis = timeBetweenLogStatsMillis;
    }

    public boolean isOracle() {
        return isOracle;
    }

    public void setOracle(boolean isOracle) {
        if (inited) {
            throw new IllegalStateException();
        }
        this.isOracle = isOracle;
    }

    public boolean isUseUnfairLock() {
        return !lock.isFair();
    }

    public void setUseUnfairLock(boolean useUnfairLock) {
        if (lock.isFair() == !useUnfairLock && this.useUnfairLock != null) {
            return;
        }

        if (!this.inited) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                if (!this.inited) {
                    this.lock = new ReentrantLock(!useUnfairLock);
                    this.notEmpty = this.lock.newCondition();
                    this.empty = this.lock.newCondition();

                    this.useUnfairLock = useUnfairLock;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public boolean isUseOracleImplicitCache() {
        return useOracleImplicitCache;
    }

    public void setUseOracleImplicitCache(boolean useOracleImplicitCache) {
        if (this.useOracleImplicitCache != useOracleImplicitCache) {
            this.useOracleImplicitCache = useOracleImplicitCache;
            boolean isOracleDriver10 = isOracle() && this.driver != null && this.driver.getMajorVersion() == 10;

            if (isOracleDriver10 && useOracleImplicitCache) {
                this.getConnectProperties().setProperty("oracle.jdbc.FreeMemoryOnEnterImplicitCache", "true");
            } else {
                this.getConnectProperties().remove("oracle.jdbc.FreeMemoryOnEnterImplicitCache");
            }
        }
    }

    public Throwable getLastCreateError() {
        return lastCreateError;
    }

    public Throwable getLastError() {
        return this.lastError;
    }

    public long getLastErrorTimeMillis() {
        return lastErrorTimeMillis;
    }

    public java.util.Date getLastErrorTime() {
        return lastErrorTimeMillis <= 0 ? null : new java.util.Date(lastErrorTimeMillis);
    }

    public long getLastCreateErrorTimeMillis() {
        return lastCreateErrorTimeMillis;
    }

    public java.util.Date getLastCreateErrorTime() {
        return lastCreateErrorTimeMillis <= 0 ? null : new java.util.Date(lastCreateErrorTimeMillis);
    }

    public int getTransactionQueryTimeout() {
        return transactionQueryTimeout <= 0 ? queryTimeout : transactionQueryTimeout;
    }

    public void setTransactionQueryTimeout(int transactionQueryTimeout) {
        this.transactionQueryTimeout = transactionQueryTimeout;
    }

    // 执行数(总共)
    public long getExecuteCount() {
        return executeCount + executeQueryCount + executeUpdateCount + executeBatchCount;
    }

    public long getExecuteUpdateCount() {
        return executeUpdateCount;
    }

    public long getExecuteQueryCount() {
        return executeQueryCount;
    }

    public long getExecuteBatchCount() {
        return executeBatchCount;
    }

    public long getAndResetExecuteCount() {
        return executeCountUpdater.getAndSet(this, 0)
                + executeQueryCountUpdater.getAndSet(this, 0)
                + executeUpdateCountUpdater.getAndSet(this, 0)
                + executeBatchCountUpdater.getAndSet(this, 0);
    }

    public long getExecuteCount2() {
        return executeCount;
    }

    public void incrementExecuteCount() {
        this.executeCountUpdater.incrementAndGet(this);
    }

    public void incrementExecuteUpdateCount() {
        this.executeUpdateCount++;
    }

    public void incrementExecuteQueryCount() {
        this.executeQueryCount++;
    }

    public void incrementExecuteBatchCount() {
        this.executeBatchCount++;
    }

    public boolean isDupCloseLogEnable() {
        return dupCloseLogEnable;
    }

    public void setDupCloseLogEnable(boolean dupCloseLogEnable) {
        this.dupCloseLogEnable = dupCloseLogEnable;
    }

    public ObjectName getObjectName() {
        return objectName;
    }

    public void setObjectName(ObjectName objectName) {
        this.objectName = objectName;
    }

    public Histogram getTransactionHistogram() {
        return transactionHistogram;
    }

    public void incrementCachedPreparedStatementCount() {
        cachedPreparedStatementCountUpdater.incrementAndGet(this);
    }

    public void decrementCachedPreparedStatementCount() {
        cachedPreparedStatementCountUpdater.decrementAndGet(this);
    }

    public void incrementCachedPreparedStatementDeleteCount() {
        cachedPreparedStatementDeleteCountUpdater.incrementAndGet(this);
    }

    public void incrementCachedPreparedStatementMissCount() {
        cachedPreparedStatementMissCountUpdater.incrementAndGet(this);
    }

    public long getCachedPreparedStatementMissCount() {
        return cachedPreparedStatementMissCount;
    }

    // PSCache访问次数
    public long getCachedPreparedStatementAccessCount() {
        return cachedPreparedStatementMissCount + cachedPreparedStatementHitCount;
    }

    public long getCachedPreparedStatementDeleteCount() {
        return cachedPreparedStatementDeleteCount;
    }

    public long getCachedPreparedStatementCount() {
        return cachedPreparedStatementCount;
    }

    public void incrementClosedPreparedStatementCount() {
        closedPreparedStatementCountUpdater.incrementAndGet(this);
    }

    public long getClosedPreparedStatementCount() {
        return closedPreparedStatementCount;
    }

    public void incrementPreparedStatementCount() {
        preparedStatementCountUpdater.incrementAndGet(this);
    }

    public long getPreparedStatementCount() {
        return preparedStatementCount;
    }

    public void incrementCachedPreparedStatementHitCount() {
        cachedPreparedStatementHitCountUpdater.incrementAndGet(this);
    }

    public long getCachedPreparedStatementHitCount() {
        return cachedPreparedStatementHitCount;
    }

    public long getTransactionThresholdMillis() {
        return transactionThresholdMillis;
    }

    public void setTransactionThresholdMillis(long transactionThresholdMillis) {
        this.transactionThresholdMillis = transactionThresholdMillis;
    }

    public abstract void logTransaction(TransactionInfo info);

    public long[] getTransactionHistogramValues() {
        return transactionHistogram.toArray();
    }

    public long[] getTransactionHistogramRanges() {
        return transactionHistogram.getRanges();
    }

    public long getCommitCount() {
        return commitCount;
    }

    public void incrementCommitCount() {
        commitCountUpdater.incrementAndGet(this);
    }

    public long getRollbackCount() {
        return rollbackCount;
    }

    public void incrementRollbackCount() {
        rollbackCountUpdater.incrementAndGet(this);
    }

    public long getStartTransactionCount() {
        return startTransactionCount;
    }

    public void incrementStartTransactionCount() {
        startTransactionCountUpdater.incrementAndGet(this);
    }

    public boolean isBreakAfterAcquireFailure() {
        return breakAfterAcquireFailure;
    }

    public void setBreakAfterAcquireFailure(boolean breakAfterAcquireFailure) {
        this.breakAfterAcquireFailure = breakAfterAcquireFailure;
    }

    public int getConnectionErrorRetryAttempts() {
        return connectionErrorRetryAttempts;
    }

    public void setConnectionErrorRetryAttempts(int connectionErrorRetryAttempts) {
        this.connectionErrorRetryAttempts = connectionErrorRetryAttempts;
    }

    public long getDupCloseCount() {
        return dupCloseCount;
    }

    public int getMaxPoolPreparedStatementPerConnectionSize() {
        return maxPoolPreparedStatementPerConnectionSize;
    }

    public void setMaxPoolPreparedStatementPerConnectionSize(int maxPoolPreparedStatementPerConnectionSize) {
        this.poolPreparedStatements = maxPoolPreparedStatementPerConnectionSize > 0;
        this.maxPoolPreparedStatementPerConnectionSize = maxPoolPreparedStatementPerConnectionSize;
    }

    public boolean isSharePreparedStatements() {
        return sharePreparedStatements;
    }

    public void setSharePreparedStatements(boolean sharePreparedStatements) {
        this.sharePreparedStatements = sharePreparedStatements;
    }

    public void incrementDupCloseCount() {
        dupCloseCountUpdater.incrementAndGet(this);
    }

    public ValidConnectionChecker getValidConnectionChecker() {
        return validConnectionChecker;
    }

    public void setValidConnectionChecker(ValidConnectionChecker validConnectionChecker) {
        this.validConnectionChecker = validConnectionChecker;
    }

    public boolean isUsePingMethod() {
        return usePingMethod;
    }

    public void setUsePingMethod(boolean usePingMethod) {
        this.usePingMethod = usePingMethod;
    }

    public String getValidConnectionCheckerClassName() {
        return validConnectionChecker == null ? null : validConnectionChecker.getClass().getName();
    }

    public void setValidConnectionCheckerClassName(String validConnectionCheckerClass) throws Exception {
        Class<?> clazz = Utils.loadClass(validConnectionCheckerClass);
        ValidConnectionChecker validConnectionChecker;
        if (clazz != null) {
            validConnectionChecker = (ValidConnectionChecker) clazz.newInstance();
            this.validConnectionChecker = validConnectionChecker;
        } else {
            if (LOG.isErrorEnabled()) {
                LOG.error("load validConnectionCheckerClass["
                        + validConnectionCheckerClass + "] error, and use JDBC4ValidConnectionChecker.");
            }
            this.validConnectionChecker = new JDBC4ValidConnectionChecker();
        }
    }

    public String getDbType() {
        return dbTypeName;
    }

    public void setDbType(DbType dbType) {
        this.dbTypeName = dbType == null ? null : dbType.name();
    }

    public void setDbType(String dbType) {
        this.dbTypeName = dbType;
    }

    public void addConnectionProperty(String name, String value) {
        if (StringUtils.equals(connectProperties.getProperty(name), value)) {
            return;
        }

        if (inited) {
            throw new UnsupportedOperationException();
        }

        connectProperties.put(name, value);
    }

    public Collection<String> getConnectionInitSqls() {
        return connectionInitSqls == null ? Collections.emptyList() : connectionInitSqls;
    }

    public void setConnectionInitSqls(Collection<? extends Object> connectionInitSqls) {
        if ((connectionInitSqls != null) && (connectionInitSqls.size() > 0)) {
            ArrayList<String> newVal = null;
            for (Object o : connectionInitSqls) {
                if (o == null) {
                    continue;
                }

                String s = o.toString();
                s = s.trim();
                if (s.length() == 0) {
                    continue;
                }

                if (newVal == null) {
                    newVal = new ArrayList<String>();
                }
                newVal.add(s);
            }
            this.connectionInitSqls = newVal;
        } else {
            this.connectionInitSqls = null;
        }
    }

    public long getTimeBetweenConnectErrorMillis() {
        return timeBetweenConnectErrorMillis;
    }

    public void setTimeBetweenConnectErrorMillis(long timeBetweenConnectErrorMillis) {
        this.timeBetweenConnectErrorMillis = timeBetweenConnectErrorMillis;
    }

    public int getMaxOpenPreparedStatements() {
        return maxPoolPreparedStatementPerConnectionSize;
    }

    public void setMaxOpenPreparedStatements(int maxOpenPreparedStatements) {
        this.setMaxPoolPreparedStatementPerConnectionSize(maxOpenPreparedStatements);
    }

    public boolean isLogAbandoned() {
        return logAbandoned;
    }

    public void setLogAbandoned(boolean logAbandoned) {
        this.logAbandoned = logAbandoned;
    }

    public int getRemoveAbandonedTimeout() {
        return (int) (removeAbandonedTimeoutMillis / 1000);
    }

    public void setRemoveAbandonedTimeout(int removeAbandonedTimeout) {
        this.removeAbandonedTimeoutMillis = (long) removeAbandonedTimeout * 1000;
    }

    public void setRemoveAbandonedTimeoutMillis(long removeAbandonedTimeoutMillis) {
        this.removeAbandonedTimeoutMillis = removeAbandonedTimeoutMillis;
    }

    public long getRemoveAbandonedTimeoutMillis() {
        return removeAbandonedTimeoutMillis;
    }

    public boolean isRemoveAbandoned() {
        return removeAbandoned;
    }

    public void setRemoveAbandoned(boolean removeAbandoned) {
        this.removeAbandoned = removeAbandoned;
    }

    public long getMinEvictableIdleTimeMillis() {
        return minEvictableIdleTimeMillis;
    }

    public void setMinEvictableIdleTimeMillis(long minEvictableIdleTimeMillis) {
        if (minEvictableIdleTimeMillis < 1000 * 30) {
            LOG.error("minEvictableIdleTimeMillis should be greater than 30000");
        }
        this.minEvictableIdleTimeMillis = minEvictableIdleTimeMillis;
    }

    public long getKeepAliveBetweenTimeMillis() {
        return keepAliveBetweenTimeMillis;
    }

    public void setKeepAliveBetweenTimeMillis(long keepAliveBetweenTimeMillis) {
        if (keepAliveBetweenTimeMillis < 1000 * 30) {
            LOG.error("keepAliveBetweenTimeMillis should be greater than 30000");
        }
        this.keepAliveBetweenTimeMillis = keepAliveBetweenTimeMillis;
    }

    public long getMaxEvictableIdleTimeMillis() {
        return maxEvictableIdleTimeMillis;
    }

    public void setMaxEvictableIdleTimeMillis(long maxEvictableIdleTimeMillis) {
        if (maxEvictableIdleTimeMillis < 1000 * 30) {
            LOG.error("maxEvictableIdleTimeMillis should be greater than 30000");
        }

        if (inited && maxEvictableIdleTimeMillis < minEvictableIdleTimeMillis) {
            throw new IllegalArgumentException("maxEvictableIdleTimeMillis must be grater than minEvictableIdleTimeMillis");
        }

        this.maxEvictableIdleTimeMillis = maxEvictableIdleTimeMillis;
    }

    public long getPhyTimeoutMillis() {
        return phyTimeoutMillis;
    }

    public void setPhyTimeoutMillis(long phyTimeoutMillis) {
        this.phyTimeoutMillis = phyTimeoutMillis;
    }

    public long getPhyMaxUseCount() {
        return phyMaxUseCount;
    }

    public void setPhyMaxUseCount(long phyMaxUseCount) {
        this.phyMaxUseCount = phyMaxUseCount;
    }

    public int getNumTestsPerEvictionRun() {
        return numTestsPerEvictionRun;
    }

    /**
     * @param numTestsPerEvictionRun
     */
    @Deprecated
    public void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
        this.numTestsPerEvictionRun = numTestsPerEvictionRun;
    }

    public long getTimeBetweenEvictionRunsMillis() {
        return timeBetweenEvictionRunsMillis;
    }

    public void setTimeBetweenEvictionRunsMillis(long timeBetweenEvictionRunsMillis) {
        this.timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
    }

    public int getMaxWaitThreadCount() {
        return maxWaitThreadCount;
    }

    public void setMaxWaitThreadCount(int maxWaithThreadCount) {
        this.maxWaitThreadCount = maxWaithThreadCount;
    }

    public String getValidationQuery() {
        return validationQuery;
    }

    public void setValidationQuery(String validationQuery) {
        this.validationQuery = validationQuery;
    }

    public int getValidationQueryTimeout() {
        return validationQueryTimeout;
    }

    public void setValidationQueryTimeout(int validationQueryTimeout) {
        if (validationQueryTimeout < 0 && DbType.of(dbTypeName) == DbType.sqlserver) {
            LOG.error("validationQueryTimeout should be >= 0");
        }
        this.validationQueryTimeout = validationQueryTimeout;
    }

    public boolean isAccessToUnderlyingConnectionAllowed() {
        return accessToUnderlyingConnectionAllowed;
    }

    public void setAccessToUnderlyingConnectionAllowed(boolean accessToUnderlyingConnectionAllowed) {
        this.accessToUnderlyingConnectionAllowed = accessToUnderlyingConnectionAllowed;
    }

    public boolean isTestOnBorrow() {
        return testOnBorrow;
    }

    public void setTestOnBorrow(boolean testOnBorrow) {
        this.testOnBorrow = testOnBorrow;
    }

    public boolean isTestOnReturn() {
        return testOnReturn;
    }

    public void setTestOnReturn(boolean testOnReturn) {
        this.testOnReturn = testOnReturn;
    }

    public boolean isTestWhileIdle() {
        return testWhileIdle;
    }

    public void setTestWhileIdle(boolean testWhileIdle) {
        this.testWhileIdle = testWhileIdle;
    }

    public boolean isDefaultAutoCommit() {
        return defaultAutoCommit;
    }

    public void setDefaultAutoCommit(boolean defaultAutoCommit) {
        this.defaultAutoCommit = defaultAutoCommit;
    }

    public Boolean getDefaultReadOnly() {
        return defaultReadOnly;
    }

    public void setDefaultReadOnly(Boolean defaultReadOnly) {
        this.defaultReadOnly = defaultReadOnly;
    }

    public Integer getDefaultTransactionIsolation() {
        return defaultTransactionIsolation;
    }

    public void setDefaultTransactionIsolation(Integer defaultTransactionIsolation) {
        this.defaultTransactionIsolation = defaultTransactionIsolation;
    }

    public String getDefaultCatalog() {
        return defaultCatalog;
    }

    public void setDefaultCatalog(String defaultCatalog) {
        this.defaultCatalog = defaultCatalog;
    }

    public PasswordCallback getPasswordCallback() {
        return passwordCallback;
    }

    public void setPasswordCallback(PasswordCallback passwordCallback) {
        this.passwordCallback = passwordCallback;
    }

    public void setPasswordCallbackClassName(String passwordCallbackClassName) throws Exception {
        Class<?> clazz = Utils.loadClass(passwordCallbackClassName);
        if (clazz != null) {
            this.passwordCallback = (PasswordCallback) clazz.newInstance();
        } else {
            LOG.error("load passwordCallback error : " + passwordCallbackClassName);
            this.passwordCallback = null;
        }
    }

    public NameCallback getUserCallback() {
        return userCallback;
    }

    public void setUserCallback(NameCallback userCallback) {
        this.userCallback = userCallback;
    }

    public boolean isInitVariants() {
        return initVariants;
    }

    public void setInitVariants(boolean initVariants) {
        this.initVariants = initVariants;
    }

    public boolean isInitGlobalVariants() {
        return initGlobalVariants;
    }

    public void setInitGlobalVariants(boolean initGlobalVariants) {
        this.initGlobalVariants = initGlobalVariants;
    }

    /**
     * Retrieves the number of seconds the driver will wait for a <code>Statement</code> object to execute. If the limit
     * is exceeded, a <code>SQLException</code> is thrown.
     *
     * @return the current query timeout limit in seconds; zero means there is no limit
     * <code>Statement</code>
     * @see #setQueryTimeout
     */
    public int getQueryTimeout() {
        return queryTimeout;
    }

    /**
     * Sets the number of seconds the driver will wait for a <code>Statement</code> object to execute to the given
     * number of seconds. If the limit is exceeded, an <code>SQLException</code> is thrown. A JDBC driver must apply
     * this limit to the <code>execute</code>, <code>executeQuery</code> and <code>executeUpdate</code> methods. JDBC
     * driver implementations may also apply this limit to <code>ResultSet</code> methods (consult your driver vendor
     * documentation for details).
     *
     * @param seconds the new query timeout limit in seconds; zero means there is no limit
     * @see #getQueryTimeout
     */
    public void setQueryTimeout(int seconds) {
        this.queryTimeout = seconds;
    }

    /**
     * @since 1.2.12
     */
    public int getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * @since 1.2.12
     */
    public void setConnectTimeout(int milliSeconds) {
        this.connectTimeout = milliSeconds;
        this.connectTimeoutStr = null;
    }

    protected void setConnectTimeout(String milliSeconds) {
        try {
            this.connectTimeout = Integer.parseInt(milliSeconds);
        } catch (Exception ignored) {
            // ignored
        }
        this.connectTimeoutStr = null;
    }

    /**
     * @since 1.2.12
     */
    public int getSocketTimeout() {
        return socketTimeout;
    }

    /**
     * @since 1.2.12
     */
    public void setSocketTimeout(int milliSeconds) {
        this.socketTimeout = milliSeconds;
        this.socketTimeoutSr = null;
    }

    protected void setSocketTimeout(String milliSeconds) {
        try {
            this.socketTimeout = Integer.parseInt(milliSeconds);
        } catch (Exception ignored) {
            // ignored
        }
        this.socketTimeoutSr = null;
    }

    public String getName() {
        if (name != null) {
            return name;
        }
        return "DataSource-" + System.identityHashCode(this);
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isPoolPreparedStatements() {
        return poolPreparedStatements;
    }

    public abstract void setPoolPreparedStatements(boolean value);

    public long getMaxWait() {
        return maxWait;
    }

    public void setMaxWait(long maxWaitMillis) {
        if (maxWaitMillis == this.maxWait) {
            return;
        }

        if (maxWaitMillis > 0 && useUnfairLock == null && !this.inited) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                if ((!this.inited) && (!lock.isFair())) {
                    this.lock = new ReentrantLock(true);
                    this.notEmpty = this.lock.newCondition();
                    this.empty = this.lock.newCondition();
                }
            } finally {
                lock.unlock();
            }
        }

        if (inited) {
            LOG.error("maxWait changed : " + this.maxWait + " -> " + maxWaitMillis);
        }

        this.maxWait = maxWaitMillis;
    }

    public int getNotFullTimeoutRetryCount() {
        return notFullTimeoutRetryCount;
    }

    public void setNotFullTimeoutRetryCount(int notFullTimeoutRetryCount) {
        this.notFullTimeoutRetryCount = notFullTimeoutRetryCount;
    }

    public int getMinIdle() {
        return minIdle;
    }

    public void setMinIdle(int value) {
        if (value == this.minIdle) {
            return;
        }

        if (inited && value > this.maxActive) {
            throw new IllegalArgumentException("minIdle greater than maxActive, " + maxActive + " < " + this.minIdle);
        }

        if (minIdle < 0) {
            throw new IllegalArgumentException("minIdle must > 0");
        }

        this.minIdle = value;
    }

    public int getMaxIdle() {
        return maxIdle;
    }

    @Deprecated
    public void setMaxIdle(int maxIdle) {
        LOG.error("maxIdle is deprecated");
        this.maxIdle = maxIdle;
    }

    public int getInitialSize() {
        return initialSize;
    }

    public void setInitialSize(int initialSize) {
        if (this.initialSize == initialSize) {
            return;
        }

        if (inited) {
            throw new UnsupportedOperationException();
        }

        this.initialSize = initialSize;
    }

    public long getCreateErrorCount() {
        return createErrorCount;
    }

    public int getMaxActive() {
        return maxActive;
    }

    public abstract void setMaxActive(int maxActive);

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        if (StringUtils.equals(this.username, username)) {
            return;
        }

        if (inited) {
            throw new UnsupportedOperationException();
        }

        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        if (StringUtils.equals(this.password, password)) {
            return;
        }

        if (inited) {
            LOG.info("password changed");
        }

        this.password = password;
    }

    public Properties getConnectProperties() {
        return connectProperties;
    }

    public abstract void setConnectProperties(Properties properties);

    public void setConnectionProperties(String connectionProperties) {
        if (connectionProperties == null || connectionProperties.trim().length() == 0) {
            setConnectProperties(null);
            return;
        }

        String[] entries = connectionProperties.split(";");
        Properties properties = new Properties();
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i];
            if (entry.length() > 0) {
                int index = entry.indexOf('=');
                if (index > 0) {
                    String name = entry.substring(0, index);
                    String value = entry.substring(index + 1);
                    properties.setProperty(name, value);
                } else {
                    // no value is empty string which is how java.util.Properties works
                    properties.setProperty(entry, "");
                }
            }
        }

        setConnectProperties(properties);
    }

    public String getUrl() {
        return jdbcUrl;
    }

    public String getRawJdbcUrl() {
        return jdbcUrl;
    }

    public void setUrl(String jdbcUrl) {
        if (StringUtils.equals(this.jdbcUrl, jdbcUrl)) {
            return;
        }

        if (inited) {
            throw new UnsupportedOperationException();
        }

        if (jdbcUrl != null) {
            jdbcUrl = jdbcUrl.trim();
        }

        this.jdbcUrl = jdbcUrl;

        // if (jdbcUrl.startsWith(ConfigFilter.URL_PREFIX)) {
        // this.filters.add(new ConfigFilter());
        // }
    }

    public String getDriverClassName() {
        return driverClass;
    }

    public void setDriverClassName(String driverClass) {
        if (driverClass != null && driverClass.length() > 256) {
            throw new IllegalArgumentException("driverClassName length > 256.");
        }

        if (JdbcConstants.ORACLE_DRIVER2.equalsIgnoreCase(driverClass)) {
            driverClass = "oracle.jdbc.OracleDriver";
            LOG.warn("oracle.jdbc.driver.OracleDriver is deprecated.Having use oracle.jdbc.OracleDriver.");
        }

        if (inited) {
            if (StringUtils.equals(this.driverClass, driverClass)) {
                return;
            }

            throw new UnsupportedOperationException();
        }

        this.driverClass = driverClass;
    }

    public ClassLoader getDriverClassLoader() {
        return driverClassLoader;
    }

    public void setDriverClassLoader(ClassLoader driverClassLoader) {
        this.driverClassLoader = driverClassLoader;
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() {
        return DriverManager.getLoginTimeout();
    }

    public Driver getDriver() {
        return driver;
    }

    public void setDriver(Driver driver) {
        this.driver = driver;
    }

    public int getDriverMajorVersion() {
        if (this.driver == null) {
            return -1;
        }

        return this.driver.getMajorVersion();
    }

    public int getDriverMinorVersion() {
        if (this.driver == null) {
            return -1;
        }

        return this.driver.getMinorVersion();
    }

    public ExceptionSorter getExceptionSorter() {
        return exceptionSorter;
    }

    public String getExceptionSorterClassName() {
        if (exceptionSorter == null) {
            return null;
        }

        return exceptionSorter.getClass().getName();
    }

    public void setExceptionSorter(ExceptionSorter exceptionSoter) {
        this.exceptionSorter = exceptionSoter;
    }

    // 兼容JBOSS
    public void setExceptionSorterClassName(String exceptionSorter) throws Exception {
        this.setExceptionSorter(exceptionSorter);
    }

    public void setExceptionSorter(String exceptionSorter) throws SQLException {
        if (exceptionSorter == null) {
            this.exceptionSorter = NullExceptionSorter.getInstance();
            return;
        }

        exceptionSorter = exceptionSorter.trim();
        if (exceptionSorter.length() == 0) {
            this.exceptionSorter = NullExceptionSorter.getInstance();
            return;
        }

        Class<?> clazz = Utils.loadClass(exceptionSorter);
        if (clazz == null) {
            LOG.error("load exceptionSorter error : " + exceptionSorter);
        } else {
            try {
                this.exceptionSorter = (ExceptionSorter) clazz.newInstance();
            } catch (Exception ex) {
                throw new SQLException("create exceptionSorter error", ex);
            }
        }
    }

    @Override
    public List<Filter> getProxyFilters() {
        return filters;
    }

    public void setProxyFilters(List<Filter> filters) {
        if (filters != null) {
            this.filters.addAll(filters);
        }
    }

    public String[] getFilterClasses() {
        List<Filter> filterConfigList = getProxyFilters();

        List<String> classes = new ArrayList<String>();
        for (Filter filter : filterConfigList) {
            classes.add(filter.getClass().getName());
        }

        return classes.toArray(new String[classes.size()]);
    }

    public void setFilters(String filters) throws SQLException {
        if (filters != null && filters.startsWith("!")) {
            filters = filters.substring(1);
            this.clearFilters();
        }
        this.addFilters(filters);
    }

    public void addFilters(String filters) throws SQLException {
        if (filters == null || filters.length() == 0) {
            return;
        }

        String[] filterArray = filters.split("\\,");

        for (String item : filterArray) {
            FilterManager.loadFilter(this.filters, item.trim());
        }
    }

    public void clearFilters() {
        if (!isClearFiltersEnable()) {
            return;
        }
        this.filters.clear();
    }

    /**
     * 使用validConnectionChecker或Statement验证连接是否正常
     * @param conn
     * @throws SQLException
     */
    public void validateConnection(Connection conn) throws SQLException {
        String query = getValidationQuery();
        if (conn.isClosed()) {
            throw new SQLException("validateConnection: connection closed");
        }

        if (validConnectionChecker != null) {
            boolean result;
            Exception error = null;
            try {
                result = validConnectionChecker.isValidConnection(conn, validationQuery, validationQueryTimeout);

                if (conn instanceof ConnectionProxyImpl) {
                    ((ConnectionProxyImpl) conn).setLastValidateTimeMillis(System.currentTimeMillis());
                }
                if (result && onFatalError) {
                    lock.lock();
                    try {
                        if (onFatalError) {
                            onFatalError = false;
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (SQLException ex) {
                throw ex;
            } catch (Exception ex) {
                result = false;
                error = ex;
            }

            if (!result) {
                SQLException sqlError = error != null ? //
                        new SQLException("validateConnection false", error) //
                        : new SQLException("validateConnection false");
                throw sqlError;
            }
            return;
        }

        if (null != query) {
            Statement stmt = null;
            ResultSet rs = null;
            try {
                stmt = conn.createStatement();
                if (getValidationQueryTimeout() > 0) {
                    stmt.setQueryTimeout(getValidationQueryTimeout());
                }
                rs = stmt.executeQuery(query);
                if (!rs.next()) {
                    throw new SQLException("validationQuery didn't return a row");
                }

                if (onFatalError) {
                    lock.lock();
                    try {
                        if (onFatalError) {
                            onFatalError = false;
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            } finally {
                if (conn instanceof ConnectionProxyImpl) {
                    ((ConnectionProxyImpl) conn).setLastValidateTimeMillis(System.currentTimeMillis());
                }
                JdbcUtils.close(rs);
                JdbcUtils.close(stmt);
            }
        }
    }

    /**
     * @deprecated
     */
    protected boolean testConnectionInternal(Connection conn) {
        return testConnectionInternal(null, conn);
    }

    protected boolean testConnectionInternal(DruidConnectionHolder holder, Connection conn) {
        String sqlFile = JdbcSqlStat.getContextSqlFile();
        String sqlName = JdbcSqlStat.getContextSqlName();

        if (sqlFile != null) {
            JdbcSqlStat.setContextSqlFile(null);
        }
        if (sqlName != null) {
            JdbcSqlStat.setContextSqlName(null);
        }
        try {
            if (validConnectionChecker != null) {
                boolean valid = validConnectionChecker.isValidConnection(conn, validationQuery, validationQueryTimeout);
                long currentTimeMillis = System.currentTimeMillis();
                if (holder != null) {
                    holder.lastValidTimeMillis = currentTimeMillis;
                    holder.lastExecTimeMillis = currentTimeMillis;
                }
                if (conn instanceof ConnectionProxyImpl) {
                    ((ConnectionProxyImpl) conn).setLastValidateTimeMillis(currentTimeMillis);
                }
                if (valid && isMySql) { // unexcepted branch
                    long lastPacketReceivedTimeMs = MySqlUtils.getLastPacketReceivedTimeMs(conn);
                    if (lastPacketReceivedTimeMs > 0) {
                        long mysqlIdleMillis = currentTimeMillis - lastPacketReceivedTimeMs;
                        if (mysqlIdleMillis >= timeBetweenEvictionRunsMillis) {
                            discardConnection(holder);
                            String errorMsg = "discard long time none received connection. "
                                    + ", jdbcUrl : " + jdbcUrl
                                    + ", version : " + VERSION.getVersionNumber()
                                    + ", lastPacketReceivedIdleMillis : " + mysqlIdleMillis;
                            LOG.warn(errorMsg);
                            return false;
                        }
                    }
                }

                if (valid && onFatalError) {
                    lock.lock();
                    try {
                        if (onFatalError) {
                            onFatalError = false;
                        }
                    } finally {
                        lock.unlock();
                    }
                }

                return valid;
            }

            if (conn.isClosed()) {
                return false;
            }

            if (null == validationQuery) {
                return true;
            }

            Statement stmt = null;
            ResultSet rset = null;
            try {
                stmt = conn.createStatement();
                if (getValidationQueryTimeout() > 0) {
                    stmt.setQueryTimeout(validationQueryTimeout);
                }
                rset = stmt.executeQuery(validationQuery);
                if (!rset.next()) {
                    return false;
                }
            } finally {
                if (conn instanceof ConnectionProxyImpl) {
                    ((ConnectionProxyImpl) conn).setLastValidateTimeMillis(System.currentTimeMillis());
                }
                JdbcUtils.close(rset);
                JdbcUtils.close(stmt);
            }

            if (onFatalError) {
                lock.lock();
                try {
                    if (onFatalError) {
                        onFatalError = false;
                    }
                } finally {
                    lock.unlock();
                }
            }

            return true;
        } catch (Throwable ex) {
            // skip
            return false;
        } finally {
            if (sqlFile != null) {
                JdbcSqlStat.setContextSqlFile(sqlFile);
            }
            if (sqlName != null) {
                JdbcSqlStat.setContextSqlName(sqlName);
            }
        }
    }

    public Set<DruidPooledConnection> getActiveConnections() {
        activeConnectionLock.lock();
        try {
            return new HashSet<DruidPooledConnection>(this.activeConnections.keySet());
        } finally {
            activeConnectionLock.unlock();
        }
    }

    public List<String> getActiveConnectionStackTrace() {
        List<String> list = new ArrayList<String>();

        for (DruidPooledConnection conn : this.getActiveConnections()) {
            list.add(Utils.toString(conn.getConnectStackTrace()));
        }

        return list;
    }

    public long getCreateTimespanNano() {
        return createTimespan;
    }

    public long getCreateTimespanMillis() {
        return createTimespan / (1000 * 1000);
    }

    @Override
    public Driver getRawDriver() {
        return driver;
    }

    public boolean isClearFiltersEnable() {
        return clearFiltersEnable;
    }

    public void setClearFiltersEnable(boolean clearFiltersEnable) {
        this.clearFiltersEnable = clearFiltersEnable;
    }

    /**
     * 连接、执行、结果、事务、元数据对应的连接id，以及变更其id的线程安全类
     */
    protected volatile long connectionIdSeed = 10000L;
    protected volatile long statementIdSeed = 20000L;
    protected volatile long resultSetIdSeed = 50000L;
    protected volatile long transactionIdSeed = 60000L;
    protected volatile long metaDataIdSeed = 80000L;
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> connectionIdSeedUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "connectionIdSeed");
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> statementIdSeedUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "statementIdSeed");
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> resultSetIdSeedUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "resultSetIdSeed");
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> transactionIdSeedUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "transactionIdSeed");
    static final AtomicLongFieldUpdater<DruidAbstractDataSource> metaDataIdSeedUpdater = AtomicLongFieldUpdater.newUpdater(DruidAbstractDataSource.class, "metaDataIdSeed");

    public long createConnectionId() {
        return connectionIdSeedUpdater.incrementAndGet(this);
    }

    public long createStatementId() {
        return statementIdSeedUpdater.getAndIncrement(this);
    }

    public long createMetaDataId() {
        return metaDataIdSeedUpdater.getAndIncrement(this);
    }

    public long createResultSetId() {
        return resultSetIdSeedUpdater.getAndIncrement(this);
    }

    @Override
    public long createTransactionId() {
        return transactionIdSeedUpdater.getAndIncrement(this);
    }

    void initStatement(DruidPooledConnection conn, Statement stmt) throws SQLException {
        boolean transaction = !conn.getConnectionHolder().underlyingAutoCommit;

        int queryTimeout = transaction ? getTransactionQueryTimeout() : getQueryTimeout();

        if (queryTimeout > 0) {
            stmt.setQueryTimeout(queryTimeout);
        }
    }

    public void handleConnectionException(DruidPooledConnection conn, Throwable t) throws SQLException {
        handleConnectionException(conn, t, null);
    }

    public abstract void handleConnectionException(
            DruidPooledConnection conn,
            Throwable t,
            String sql
    ) throws SQLException;

    protected abstract void recycle(DruidPooledConnection pooledConnection) throws SQLException;

    /**
     * 创建物理连接，更新连接创建数量
     * @param url
     * @param info
     * @return
     * @throws SQLException
     */
    public Connection createPhysicalConnection(String url, Properties info) throws SQLException {
        Connection conn;
        if (getProxyFilters().isEmpty()) {
            conn = getDriver().connect(url, info);
        } else {
            FilterChainImpl filterChain = createChain();
            conn = filterChain.connection_connect(info);
            recycleFilterChain(filterChain);
        }

        createCountUpdater.incrementAndGet(this);

        return conn;
    }

    /**
     * 创建物理连接
     *    1、创建物理连接
     *    2、初始化物理连接
     *      （1）设置事务是否自动提交、是否只读、事务隔离级别、日志目录、执行初始化sql、参数Map集合、全局参数Map集合
     *    3、使用validConnectionChecker或Statement验证连接是否正常
     *    4、更新物理连接创建时间和创建数量统计，重置连续失败标志和创建失败信息
     *    5、将物理连接封装为 PhysicalConnectionInfo，其包括了：物理连接、连接开始时间、连接初始化时间、连接验证时间、参数Map集合、全局参数Map集合
     *
     * @return
     * @throws SQLException
     */
    public PhysicalConnectionInfo createPhysicalConnection() throws SQLException {
        String url = this.getUrl();
        Properties connectProperties = getConnectProperties();

        String user;
        if (getUserCallback() != null) {
            user = getUserCallback().getName();
        } else {
            user = getUsername();
        }

        String password = getPassword();
        PasswordCallback passwordCallback = getPasswordCallback();

        if (passwordCallback != null) {
            if (passwordCallback instanceof DruidPasswordCallback) {
                DruidPasswordCallback druidPasswordCallback = (DruidPasswordCallback) passwordCallback;

                druidPasswordCallback.setUrl(url);
                druidPasswordCallback.setProperties(connectProperties);
            }

            char[] chars = passwordCallback.getPassword();
            if (chars != null) {
                password = new String(chars);
            }
        }

        Properties physicalConnectProperties = new Properties();
        if (connectProperties != null) {
            physicalConnectProperties.putAll(connectProperties);
        }

        if (user != null && user.length() != 0) {
            physicalConnectProperties.put("user", user);
        }

        if (password != null && password.length() != 0) {
            physicalConnectProperties.put("password", password);
        }

        if (connectTimeout > 0) {
            if (isMySql) {
                if (connectTimeoutStr == null) {
                    connectTimeoutStr = Integer.toString(connectTimeout);
                }

                physicalConnectProperties.put("connectTimeout", connectTimeoutStr);
            } else if (isOracle) {
                if (connectTimeoutStr == null) {
                    connectTimeoutStr = Integer.toString(connectTimeout);
                }

                physicalConnectProperties.put("oracle.net.CONNECT_TIMEOUT", connectTimeoutStr);
            } else if (driver != null && POSTGRESQL_DRIVER.equals(driver.getClass().getName())) {
                // see https://github.com/pgjdbc/pgjdbc/blob/2b90ad04696324d107b65b085df4b1db8f6c162d/README.md
                physicalConnectProperties.put("loginTimeout", Long.toString(TimeUnit.MILLISECONDS.toSeconds(connectTimeout)));
                physicalConnectProperties.put("connectTimeout", Long.toString(TimeUnit.MILLISECONDS.toSeconds(connectTimeout)));
                if (socketTimeout > 0) {
                    physicalConnectProperties.put("socketTimeout", Long.toString(TimeUnit.MILLISECONDS.toSeconds(socketTimeout)));
                }
            } else if (DbType.sqlserver.name().equals(dbTypeName)) {
                // see https://learn.microsoft.com/en-us/sql/connect/jdbc/setting-the-connection-properties?view=sql-server-ver16
                physicalConnectProperties.put("loginTimeout", Long.toString(TimeUnit.MILLISECONDS.toSeconds(connectTimeout)));
                if (socketTimeout > 0) {
                    // As SQLServer-jdbc-driver 6.1.2 can use this, see https://github.com/microsoft/mssql-jdbc/wiki/SocketTimeout
                    physicalConnectProperties.put("socketTimeout", Integer.toString(socketTimeout));
                }
            }
        }

        Connection conn = null;

        long connectStartNanos = System.nanoTime();
        long connectedNanos, initedNanos, validatedNanos;

        Map<String, Object> variables = initVariants
                ? new HashMap<String, Object>()
                : null;
        Map<String, Object> globalVariables = initGlobalVariants
                ? new HashMap<String, Object>()
                : null;

        createStartNanosUpdater.set(this, connectStartNanos);
        creatingCountUpdater.incrementAndGet(this);
        try {
            conn = createPhysicalConnection(url, physicalConnectProperties);
            connectedNanos = System.nanoTime();

            if (conn == null) {
                throw new SQLException("connect error, url " + url + ", driverClass " + this.driverClass);
            }

            initPhysicalConnection(conn, variables, globalVariables);
            initedNanos = System.nanoTime();

            boolean skipSocketTimeout = "odps".equals(dbTypeName);
            if (socketTimeout > 0 && !netTimeoutError && !skipSocketTimeout) {
                try {
                    // As SQLServer-jdbc-driver 6.1.7 can use this, see https://github.com/microsoft/mssql-jdbc/wiki/SocketTimeout
                    conn.setNetworkTimeout(netTimeoutExecutor, socketTimeout); // here is milliseconds defined by JDBC
                } catch (SQLFeatureNotSupportedException | AbstractMethodError e) {
                    netTimeoutError = true;
                } catch (Exception ignored) {
                    // ignored
                }
            }

            // 使用validConnectionChecker或Statement验证连接是否正常
            validateConnection(conn);
            validatedNanos = System.nanoTime();

            setFailContinuous(false);
            setCreateError(null);
        } catch (SQLException ex) {
            setCreateError(ex);
            JdbcUtils.close(conn);
            throw ex;
        } catch (RuntimeException ex) {
            setCreateError(ex);
            JdbcUtils.close(conn);
            throw ex;
        } catch (Error ex) {
            createErrorCountUpdater.incrementAndGet(this);
            setCreateError(ex);
            JdbcUtils.close(conn);
            throw ex;
        } finally {
            long nano = System.nanoTime() - connectStartNanos;
            createTimespan += nano;
            creatingCountUpdater.decrementAndGet(this);
        }

        return new PhysicalConnectionInfo(conn, connectStartNanos, connectedNanos, initedNanos, validatedNanos, variables, globalVariables);
    }

    /**
     * 更新创建错误信息
     *      更新创建错误内容，如果没有错误，可以清空错误信息
     *      如果有错误，需要更新创建异常、最近一次创建异常、以及最近一次创建异常的时间
     * @param ex
     */
    protected void setCreateError(Throwable ex) {
        if (ex == null) {
            lock.lock();
            try {
                if (createError != null) {
                    createError = null;
                }
            } finally {
                lock.unlock();
            }
            return;
        }

        createErrorCountUpdater.incrementAndGet(this);
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            createError = ex;
            lastCreateError = ex;
            lastCreateErrorTimeMillis = now;
        } finally {
            lock.unlock();
        }
    }

    public boolean isFailContinuous() {
        return failContinuousUpdater.get(this) == 1;
    }

    /**
     * 更新连续失败的状态和时间
     * @param fail
     */
    protected void setFailContinuous(boolean fail) {
        // 更新连续失败的时间
        if (fail) {
            failContinuousTimeMillisUpdater.set(this, System.currentTimeMillis());
        } else {
            failContinuousTimeMillisUpdater.set(this, 0L);
        }

        boolean currentState = failContinuousUpdater.get(this) == 1;
        if (currentState == fail) {
            return;
        }
        // 更新连续失败标志
        if (fail) {
            failContinuousUpdater.set(this, 1);
            if (LOG.isInfoEnabled()) {
                LOG.info("{dataSource-" + this.getID() + "} failContinuous is true");
            }
        } else {
            failContinuousUpdater.set(this, 0);
            if (LOG.isInfoEnabled()) {
                LOG.info("{dataSource-" + this.getID() + "} failContinuous is false");
            }
        }
    }

    public void initPhysicalConnection(Connection conn) throws SQLException {
        initPhysicalConnection(conn, null, null);
    }

    /**
     * 初始化物理连接
     *      （1）设置事务是否自动提交、是否只读、事务隔离级别、日志目录、执行初始化sql、参数Map集合、全局参数Map集合
     * @param conn
     * @param variables
     * @param globalVariables
     * @throws SQLException
     */
    public void initPhysicalConnection(Connection conn,
                                       Map<String, Object> variables,
                                       Map<String, Object> globalVariables) throws SQLException {
        boolean skipAutoCommit = "odps".equals(dbTypeName);

        if ((!skipAutoCommit) && conn.getAutoCommit() != defaultAutoCommit) {
            conn.setAutoCommit(defaultAutoCommit);
        }

        if (defaultReadOnly != null) {
            if (conn.isReadOnly() != defaultReadOnly) {
                conn.setReadOnly(defaultReadOnly);
            }
        }

        if (getDefaultTransactionIsolation() != null) {
            if (conn.getTransactionIsolation() != getDefaultTransactionIsolation().intValue()) {
                conn.setTransactionIsolation(getDefaultTransactionIsolation());
            }
        }

        if (getDefaultCatalog() != null && getDefaultCatalog().length() != 0) {
            conn.setCatalog(getDefaultCatalog());
        }

        Collection<String> initSqls = getConnectionInitSqls();
        if (initSqls.isEmpty()
                && variables == null
                && globalVariables == null) {
            return;
        }

        Statement stmt = null;
        try {
            stmt = conn.createStatement();

            for (String sql : initSqls) {
                if (sql == null) {
                    continue;
                }

                stmt.execute(sql);
            }

            DbType dbType = DbType.of(this.dbTypeName);
            if (JdbcUtils.isMysqlDbType(dbType)) {
                if (variables != null) {
                    ResultSet rs = null;
                    try {
                        rs = stmt.executeQuery("show variables");
                        while (rs.next()) {
                            String name = rs.getString(1);
                            Object value = rs.getObject(2);
                            variables.put(name, value);
                        }
                    } finally {
                        JdbcUtils.close(rs);
                    }
                }

                if (globalVariables != null) {
                    ResultSet rs = null;
                    try {
                        rs = stmt.executeQuery("show global variables");
                        while (rs.next()) {
                            String name = rs.getString(1);
                            Object value = rs.getObject(2);
                            globalVariables.put(name, value);
                        }
                    } finally {
                        JdbcUtils.close(rs);
                    }
                }
            }
        } finally {
            JdbcUtils.close(stmt);
        }
    }

    public abstract int getActivePeak();

    public CompositeDataSupport getCompositeData() throws JMException {
        JdbcDataSourceStat stat = this.getDataSourceStat();

        Map<String, Object> map = new HashMap<String, Object>();

        map.put("ID", getID());
        map.put("URL", this.getUrl());
        map.put("Name", this.getName());
        map.put("FilterClasses", getFilterClasses());
        map.put("CreatedTime", getCreatedTime());

        map.put("RawDriverClassName", getDriverClassName());
        map.put("RawUrl", getUrl());
        map.put("RawDriverMajorVersion", getRawDriverMajorVersion());
        map.put("RawDriverMinorVersion", getRawDriverMinorVersion());
        map.put("Properties", getProperties());

        // 0 - 4
        map.put("ConnectionActiveCount", (long) getActiveCount());
        map.put("ConnectionActiveCountMax", getActivePeak());
        map.put("ConnectionCloseCount", getCloseCount());
        map.put("ConnectionCommitCount", getCommitCount());
        map.put("ConnectionRollbackCount", getRollbackCount());

        // 5 - 9
        map.put("ConnectionConnectLastTime", stat.getConnectionStat().getConnectLastTime());
        map.put("ConnectionConnectErrorCount", this.getCreateCount());
        if (createError != null) {
            map.put("ConnectionConnectErrorLastTime", getLastCreateErrorTime());
            map.put("ConnectionConnectErrorLastMessage", createError.getMessage());
            map.put("ConnectionConnectErrorLastStackTrace", Utils.getStackTrace(createError));
        } else {
            map.put("ConnectionConnectErrorLastTime", null);
            map.put("ConnectionConnectErrorLastMessage", null);
            map.put("ConnectionConnectErrorLastStackTrace", null);
        }

        // 10 - 14
        map.put("StatementCreateCount", stat.getStatementStat().getCreateCount());
        map.put("StatementPrepareCount", stat.getStatementStat().getPrepareCount());
        map.put("StatementPreCallCount", stat.getStatementStat().getPrepareCallCount());
        map.put("StatementExecuteCount", stat.getStatementStat().getExecuteCount());
        map.put("StatementRunningCount", stat.getStatementStat().getRunningCount());

        // 15 - 19
        map.put("StatementConcurrentMax", stat.getStatementStat().getConcurrentMax());
        map.put("StatementCloseCount", stat.getStatementStat().getCloseCount());
        map.put("StatementErrorCount", stat.getStatementStat().getErrorCount());
        map.put("StatementLastErrorTime", null);
        map.put("StatementLastErrorMessage", null);

        // 20 - 24
        map.put("StatementLastErrorStackTrace", null);
        map.put("StatementExecuteMillisTotal", stat.getStatementStat().getMillisTotal());
        map.put("StatementExecuteLastTime", stat.getStatementStat().getExecuteLastTime());
        map.put("ConnectionConnectingCount", stat.getConnectionStat().getConnectingCount());
        map.put("ResultSetCloseCount", stat.getResultSetStat().getCloseCount());

        // 25 - 29
        map.put("ResultSetOpenCount", stat.getResultSetStat().getOpenCount());
        map.put("ResultSetOpenningCount", stat.getResultSetStat().getOpeningCount());
        map.put("ResultSetOpenningMax", stat.getResultSetStat().getOpeningMax());
        map.put("ResultSetFetchRowCount", stat.getResultSetStat().getFetchRowCount());
        map.put("ResultSetLastOpenTime", stat.getResultSetStat().getLastOpenTime());

        // 30 - 34
        map.put("ResultSetErrorCount", stat.getResultSetStat().getErrorCount());
        map.put("ResultSetOpenningMillisTotal", stat.getResultSetStat().getAliveMillisTotal());
        map.put("ResultSetLastErrorTime", stat.getResultSetStat().getLastErrorTime());
        map.put("ResultSetLastErrorMessage", null);
        map.put("ResultSetLastErrorStackTrace", null);

        // 35 - 39
        map.put("ConnectionConnectCount", this.getConnectCount());
        if (createError != null) {
            map.put("ConnectionErrorLastMessage", createError.getMessage());
            map.put("ConnectionErrorLastStackTrace", Utils.getStackTrace(createError));
        } else {
            map.put("ConnectionErrorLastMessage", null);
            map.put("ConnectionErrorLastStackTrace", null);
        }
        map.put("ConnectionConnectMillisTotal", stat.getConnectionStat().getConnectMillis());
        map.put("ConnectionConnectingCountMax", stat.getConnectionStat().getConnectingMax());

        // 40 - 44
        map.put("ConnectionConnectMillisMax", stat.getConnectionStat().getConnectMillisMax());
        map.put("ConnectionErrorLastTime", stat.getConnectionStat().getErrorLastTime());
        map.put("ConnectionAliveMillisMax", stat.getConnectionConnectAliveMillisMax());
        map.put("ConnectionAliveMillisMin", stat.getConnectionConnectAliveMillisMin());

        map.put("ConnectionHistogram", stat.getConnectionHistogramValues());
        map.put("StatementHistogram", stat.getStatementStat().getHistogramValues());

        return new CompositeDataSupport(JdbcStatManager.getDataSourceCompositeType(), map);
    }

    public long getID() {
        return this.id;
    }

    @Override
    public long getDataSourceId() {
        return getID();
    }
    public java.util.Date getCreatedTime() {
        return createdTime;
    }

    public abstract int getRawDriverMajorVersion();

    public abstract int getRawDriverMinorVersion();

    public abstract String getProperties();

    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    public void closePreapredStatement(PreparedStatementHolder stmtHolder) {
        if (stmtHolder == null) {
            return;
        }
        closedPreparedStatementCountUpdater.incrementAndGet(this);
        decrementCachedPreparedStatementCount();
        incrementCachedPreparedStatementDeleteCount();

        JdbcUtils.close(stmtHolder.statement);
    }

    protected void cloneTo(DruidAbstractDataSource to) {
        to.defaultAutoCommit = this.defaultAutoCommit;
        to.defaultReadOnly = this.defaultReadOnly;
        to.defaultTransactionIsolation = this.defaultTransactionIsolation;
        to.defaultCatalog = this.defaultCatalog;
        to.name = this.name;
        to.username = this.username;
        to.password = this.password;
        to.jdbcUrl = this.jdbcUrl;
        to.driverClass = this.driverClass;
        to.connectProperties = this.connectProperties;
        to.passwordCallback = this.passwordCallback;
        to.userCallback = this.userCallback;
        to.initialSize = this.initialSize;
        to.maxActive = this.maxActive;
        to.minIdle = this.minIdle;
        to.maxIdle = this.maxIdle;
        to.maxWait = this.maxWait;
        to.validationQuery = this.validationQuery;
        to.validationQueryTimeout = this.validationQueryTimeout;
        to.testOnBorrow = this.testOnBorrow;
        to.testOnReturn = this.testOnReturn;
        to.testWhileIdle = this.testWhileIdle;
        to.poolPreparedStatements = this.poolPreparedStatements;
        to.sharePreparedStatements = this.sharePreparedStatements;
        to.maxPoolPreparedStatementPerConnectionSize = this.maxPoolPreparedStatementPerConnectionSize;
        to.logWriter = this.logWriter;
        if (this.filters != null) {
            to.filters = new ArrayList<>(this.filters);
        }
        to.exceptionSorter = this.exceptionSorter;
        to.driver = this.driver;
        to.queryTimeout = this.queryTimeout;
        to.transactionQueryTimeout = this.transactionQueryTimeout;
        to.accessToUnderlyingConnectionAllowed = this.accessToUnderlyingConnectionAllowed;
        to.timeBetweenEvictionRunsMillis = this.timeBetweenEvictionRunsMillis;
        to.numTestsPerEvictionRun = this.numTestsPerEvictionRun;
        to.minEvictableIdleTimeMillis = this.minEvictableIdleTimeMillis;
        to.removeAbandoned = this.removeAbandoned;
        to.removeAbandonedTimeoutMillis = this.removeAbandonedTimeoutMillis;
        to.logAbandoned = this.logAbandoned;
        to.maxOpenPreparedStatements = this.maxOpenPreparedStatements;
        if (connectionInitSqls != null) {
            to.connectionInitSqls = new ArrayList<String>(this.connectionInitSqls);
        }
        to.dbTypeName = this.dbTypeName;
        to.timeBetweenConnectErrorMillis = this.timeBetweenConnectErrorMillis;
        to.validConnectionChecker = this.validConnectionChecker;
        to.connectionErrorRetryAttempts = this.connectionErrorRetryAttempts;
        to.breakAfterAcquireFailure = this.breakAfterAcquireFailure;
        to.transactionThresholdMillis = this.transactionThresholdMillis;
        to.dupCloseLogEnable = this.dupCloseLogEnable;
        to.isOracle = this.isOracle;
        to.useOracleImplicitCache = this.useOracleImplicitCache;
        to.asyncCloseConnectionEnable = this.asyncCloseConnectionEnable;
        to.createScheduler = this.createScheduler;
        to.destroyScheduler = this.destroyScheduler;
        to.socketTimeout = this.socketTimeout;
        to.connectTimeout = this.connectTimeout;
        to.socketTimeoutSr = this.socketTimeoutSr;
        to.connectTimeoutStr = this.connectTimeoutStr;
    }

    public abstract void discardConnection(Connection realConnection);

    public void discardConnection(DruidConnectionHolder holder) {
        discardConnection(holder.getConnection());
    }

    public boolean isAsyncCloseConnectionEnable() {
        if (isRemoveAbandoned()) {
            return true;
        }
        return asyncCloseConnectionEnable;
    }

    public void setAsyncCloseConnectionEnable(boolean asyncCloseConnectionEnable) {
        this.asyncCloseConnectionEnable = asyncCloseConnectionEnable;
    }

    public ScheduledExecutorService getCreateScheduler() {
        return createScheduler;
    }

    public void setCreateScheduler(ScheduledExecutorService createScheduler) {
        if (isInited()) {
            throw new DruidRuntimeException("dataSource inited.");
        }
        this.createScheduler = createScheduler;
    }

    public ScheduledExecutorService getDestroyScheduler() {
        return destroyScheduler;
    }

    public void setDestroyScheduler(ScheduledExecutorService destroyScheduler) {
        if (isInited()) {
            throw new DruidRuntimeException("dataSource inited.");
        }
        this.destroyScheduler = destroyScheduler;
    }

    public boolean isInited() {
        return this.inited;
    }

    public int getMaxCreateTaskCount() {
        return maxCreateTaskCount;
    }

    public void setMaxCreateTaskCount(int maxCreateTaskCount) {
        if (maxCreateTaskCount < 1) {
            throw new IllegalArgumentException();
        }

        this.maxCreateTaskCount = maxCreateTaskCount;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public int getOnFatalErrorMaxActive() {
        return onFatalErrorMaxActive;
    }

    public void setOnFatalErrorMaxActive(int onFatalErrorMaxActive) {
        this.onFatalErrorMaxActive = onFatalErrorMaxActive;
    }

    public boolean isOnFatalError() {
        return onFatalError;
    }

    /**
     * @since 1.1.11
     */
    public boolean isInitExceptionThrow() {
        return initExceptionThrow;
    }

    /**
     * @since 1.1.11
     */
    public void setInitExceptionThrow(boolean initExceptionThrow) {
        this.initExceptionThrow = initExceptionThrow;
    }

    public static class PhysicalConnectionInfo {
        private Connection connection;
        private long connectStartNanos;
        private long connectedNanos;
        private long initedNanos;
        private long validatedNanos;
        private Map<String, Object> vairiables;
        private Map<String, Object> globalVairiables;

        long createTaskId;

        public PhysicalConnectionInfo(
                Connection connection,
                long connectStartNanos,
                long connectedNanos,
                long initedNanos,
                long validatedNanos
        ) {
            this(connection, connectStartNanos, connectedNanos, initedNanos, validatedNanos, null, null);
        }

        public PhysicalConnectionInfo(
                Connection connection,
                long connectStartNanos,
                long connectedNanos,
                long initedNanos,
                long validatedNanos,
                Map<String, Object> vairiables,
                Map<String, Object> globalVairiables
        ) {
            this.connection = connection;

            this.connectStartNanos = connectStartNanos;
            this.connectedNanos = connectedNanos;
            this.initedNanos = initedNanos;
            this.validatedNanos = validatedNanos;
            this.vairiables = vairiables;
            this.globalVairiables = globalVairiables;
        }

        public Connection getPhysicalConnection() {
            return connection;
        }

        public long getConnectStartNanos() {
            return connectStartNanos;
        }

        public long getConnectedNanos() {
            return connectedNanos;
        }

        public long getInitedNanos() {
            return initedNanos;
        }

        public long getValidatedNanos() {
            return validatedNanos;
        }

        public long getConnectNanoSpan() {
            return connectedNanos - connectStartNanos;
        }

        public Map<String, Object> getVairiables() {
            return vairiables;
        }

        public Map<String, Object> getGlobalVairiables() {
            return globalVairiables;
        }
    }

    class SynchronousExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            try {
                command.run();
            } catch (AbstractMethodError error) {
                netTimeoutError = true;
            } catch (Exception ignored) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("failed to execute command " + command);
                }
            }
        }
    }
}
