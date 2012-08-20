/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomee.jdbc;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.openejb.monitoring.LocalMBeanServer;
import org.apache.openejb.monitoring.ObjectNameBuilder;
import org.apache.openejb.resource.jdbc.pool.PoolDataSourceCreator;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.openejb.util.reflection.Reflections;
import org.apache.tomcat.jdbc.pool.ConnectionPool;
import org.apache.tomcat.jdbc.pool.PoolConfiguration;
import org.apache.tomcat.jdbc.pool.PoolProperties;
import org.apache.tomcat.jdbc.pool.PooledConnection;

import javax.management.ObjectName;
import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

public class TomEEDataSourceCreator extends PoolDataSourceCreator {
    private static final Logger LOGGER = Logger.getInstance(LogCategory.OPENEJB, TomEEDataSourceCreator.class);

    @Override
    public DataSource pool(final String name, final DataSource ds, Properties properties) {
        final Properties converted = new Properties();
        updateProperties(properties, converted, null);

        final PoolConfiguration config = build(PoolProperties.class, converted);
        config.setDataSource(ds);
        final ConnectionPool pool;
        try {
            pool = new ConnectionPool(config);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return build(TomEEDataSource.class, new TomEEDataSource(config, pool, name), converted);
    }

    @Override
    public DataSource pool(final String name, final String driver, final Properties properties) {
        final Properties converted = new Properties();
        converted.setProperty("name", name);
        updateProperties(properties, converted, driver);
        final PoolConfiguration config = build(PoolProperties.class, converted);
        return build(TomEEDataSource.class, new TomEEDataSource(config, name), converted);
    }

    private void updateProperties(final Properties properties, final Properties converted, final String driver) {
        // some compatibility with old dbcp style
        if (driver != null) {
            converted.setProperty("driverClassName", driver);
        }
        if (properties.getProperty("JdbcDriver") != null) {
            converted.setProperty("driverClassName", (String) properties.remove("JdbcDriver"));
        }
        if (properties.containsKey("JdbcUrl")) {
            converted.setProperty("url", (String) properties.remove("JdbcUrl"));
        }
        if (properties.containsKey("user")) {
            converted.setProperty("username", (String) properties.remove("user"));
        }
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            final String key = entry.getKey().toString();
            final String value = entry.getValue().toString().trim();
            if (!value.isEmpty() && !converted.containsKey(key)) {
                if ("PasswordCipher".equals(key) && "PlainText".equals(value)) { // no need to warn about it
                    continue;
                }
                if ("MaxOpenPreparedStatements".equalsIgnoreCase(key) || "PoolPreparedStatements".equalsIgnoreCase(key)) {
                    String interceptors = properties.getProperty("jdbcInterceptors");
                    if (interceptors == null) {
                        interceptors = properties.getProperty("JdbcInterceptors");
                    }
                    if (interceptors == null || !interceptors.contains("StatementCache")) {
                        LOGGER.warning("Tomcat-jdbc doesn't support '" + key + "' property, please configure the StatementCache jdbc interceptor");
                    }
                    continue;
                }

                converted.put(key, value);
            }
        }
    }

    @Override
    public void doDestroy(final DataSource object) throws Throwable {
        org.apache.tomcat.jdbc.pool.DataSource ds = (org.apache.tomcat.jdbc.pool.DataSource) object;
        if (ds instanceof TomEEDataSource) {
            ((TomEEDataSource) ds).internalJMXUnregister();
        }
        ds.close(true);
    }

    public static class TomEEDataSource extends org.apache.tomcat.jdbc.pool.DataSource {
        private static final Log LOGGER = LogFactory.getLog(TomEEDataSource.class);

        private ObjectName internalOn = null;

        public TomEEDataSource(final PoolConfiguration properties, final ConnectionPool pool, final String name) {
            super(properties);
            this.pool = pool;
            initJmx(name);
        }

        @Override
        public ConnectionPool createPool() throws SQLException {
            if (pool != null) {
                return pool;
            } else {
                pool = new TomEEConnectionPool(poolProperties); // to force to init the driver with TCCL
                return pool;
            }
        }

        public TomEEDataSource(final PoolConfiguration poolConfiguration, final String name) {
            super(poolConfiguration);
            try { // just to force the pool to be created and be able to register the mbean
                createPool();
                initJmx(name);
            } catch (Throwable ignored) {
                // no-op
            }
        }

        private void initJmx(final String name) {
            try {
                internalOn = ObjectNameBuilder.uniqueName("datasources", name, this);
                try {
                    if (pool.getJmxPool()!=null) {
                        LocalMBeanServer.get().registerMBean(pool.getJmxPool(), internalOn);
                    }
                } catch (Exception e) {
                    LOGGER.error("Unable to register JDBC pool with JMX", e);
                }
            } catch (Exception ignored) {
                // no-op
            }
        }

        public void internalJMXUnregister() {
            if (internalOn != null) {
                try {
                    LocalMBeanServer.get().unregisterMBean(internalOn);
                } catch (Exception e) {
                    LOGGER.error("Unable to unregister JDBC pool with JMX", e);
                }
            }
        }

        @Override
        public Connection getConnection() throws SQLException {
            final Connection connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] { Connection.class }, new ContantHashCodeHandler(connection, connection.hashCode()));
        }

        @Override
        public Connection getConnection(final String u, final String p) throws SQLException {
            final Connection connection = super.getConnection(u, p);
            return (Connection) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] { Connection.class }, new ContantHashCodeHandler(connection, connection.hashCode()));
        }
    }

    private static class TomEEConnectionPool extends ConnectionPool {
        public TomEEConnectionPool(final PoolConfiguration poolProperties) throws SQLException {
            super(poolProperties);
        }

        @Override
        protected PooledConnection create(boolean incrementCounter) {
            final PooledConnection con = super.create(incrementCounter);
            if (getPoolProperties().getDataSource() == null) { // using driver
                // init driver with TCCL
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) {
                    cl = TomEEConnectionPool.class.getClassLoader();
                }
                try {
                    Reflections.set(con, "driver", Class.forName(getPoolProperties().getDriverClassName(), true, cl).newInstance());
                } catch (java.lang.Exception cn) {
                    // will fail later, no worry
                }
            }
            return con;
        }
    }

    private static class ContantHashCodeHandler implements InvocationHandler { // will be fixed in tomcat-jdbc in next version
        private final Object delegate;
        private final int hashCode;

        public ContantHashCodeHandler(final Object object, int hashCode) {
            this.delegate = object;
            this.hashCode = hashCode;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("hashCode".equals(method.getName())) {
                return hashCode;
            }
            return method.invoke(delegate, args);
        }
    }
}