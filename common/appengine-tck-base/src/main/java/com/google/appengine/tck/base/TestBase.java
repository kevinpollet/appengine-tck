/*
 * Copyright 2013 Google Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.appengine.tck.base;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Transaction;
import com.google.appengine.api.datastore.TransactionOptions;
import com.google.appengine.tck.category.IgnoreMultisuite;
import com.google.appengine.tck.event.ExecutionLifecycleEvent;
import com.google.appengine.tck.event.InstanceLifecycleEvent;
import com.google.appengine.tck.event.Property;
import com.google.appengine.tck.event.PropertyLifecycleEvent;
import com.google.appengine.tck.event.TestLifecycleEvent;
import com.google.appengine.tck.event.TestLifecycles;
import com.google.appengine.tck.temp.TempData;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;

/**
 * Base test class for all GAE TCK tests.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class TestBase {
    protected static final long DEFAULT_SLEEP = 3000L;
    protected static final String TCK_PROPERTIES = "tck.properties";
    protected static final String TIMESTAMP_TXT = "timestamp.txt";

    protected final Logger log = Logger.getLogger(getClass().getName());

    private Object timestamp;

    protected static void enhanceTestContext(TestContext context) {
        TestLifecycleEvent event = TestLifecycles.createTestContextLifecycleEvent(null, context);
        TestLifecycles.before(event);
    }

    protected static WebArchive getTckDeployment() {
        return getTckDeployment(new TestContext());
    }

    protected static WebArchive getTckDeployment(TestContext context) {
        enhanceTestContext(context);

        final WebArchive war;

        String archiveName = context.getArchiveName();
        if (archiveName != null) {
            if (archiveName.endsWith(".war") == false) archiveName += ".war";
            war = ShrinkWrap.create(WebArchive.class, archiveName);
        } else {
            war = ShrinkWrap.create(WebArchive.class);
        }

        // this package
        war.addPackage(TestBase.class.getPackage());
        // categories
        war.addPackage(IgnoreMultisuite.class.getPackage());
        // events
        war.addPackage(TestLifecycles.class.getPackage());
        // temp data
        war.addPackage(TempData.class.getPackage());

        // web.xml
        if (context.getWebXmlFile() != null) {
            war.setWebXML(context.getWebXmlFile());
        } else {
            war.setWebXML(new StringAsset(context.getWebXmlContent()));
        }

        // context-root
        if (context.getContextRoot() != null) {
            war.addAsWebInfResource(context.getContextRoot().getDescriptor());
        }

        // appengine-web.xml
        if (context.getAppEngineWebXmlFile() != null) {
            war.addAsWebInfResource(context.getAppEngineWebXmlFile(), "appengine-web.xml");
        } else {
            war.addAsWebInfResource("appengine-web.xml");
        }

        if (context.hasCallbacks()) {
            war.addAsWebInfResource("META-INF/datastorecallbacks.xml", "classes/META-INF/datastorecallbacks.xml");
        }

        if (context.getCompatibilityProperties() != null && (context.getProperties().isEmpty() == false || context.isUseSystemProperties())) {
            Properties properties = new Properties();

            if (context.isUseSystemProperties()) {
                properties.putAll(System.getProperties());
            }
            properties.putAll(context.getProperties());

            final StringWriter writer = new StringWriter();
            try {
                properties.store(writer, "GAE TCK testing!");
            } catch (IOException e) {
                throw new RuntimeException("Cannot write compatibility properties.", e);
            }

            final StringAsset asset = new StringAsset(writer.toString());
            war.addAsWebInfResource(asset, "classes/" + context.getCompatibilityProperties());
        }

        if (context.isIgnoreTimestamp() == false) {
            war.addAsWebInfResource(new StringAsset(String.valueOf(context.getTimestamp())), "classes/" + TIMESTAMP_TXT);
        }

        return war;
    }

    /**
     * Should work in all envs?
     * A bit complex / overkill ...
     *
     * @return true if in-container, false otherewise
     */
    protected static boolean isInContainer() {
        try {
            DatastoreService ds = DatastoreServiceFactory.getDatastoreService();
            Transaction tx = ds.beginTransaction();
            try {
                return (ds.getCurrentTransaction() != null);
            } finally {
                tx.rollback();
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    protected static void assertRegexpMatches(String regexp, String str) {
        Assert.assertTrue("Expected to match regexp " + regexp + " but was: " + str, str != null && str.matches(regexp));
    }

    protected boolean execute(String context) {
        Boolean result = executeRaw(context);
        return (result != null && result);
    }

    protected Boolean executeRaw(String context) {
        ExecutionLifecycleEvent event = TestLifecycles.createExecutionLifecycleEvent(getClass(), context);
        TestLifecycles.before(event);
        return event.execute();
    }

    protected boolean required(String propertyName) {
        Property result = property(propertyName);
        Boolean required = result.required();
        return (required == null || required); // by default null means it's required
    }

    protected boolean doIgnore(String context) {
        return execute(context) == false;
    }

    protected Property property(String propertyName) {
        PropertyLifecycleEvent event = TestLifecycles.createPropertyLifecycleEvent(getClass(), propertyName);
        TestLifecycles.before(event);
        return event;
    }

    protected <T> T instance(Class<T> instanceType) {
        InstanceLifecycleEvent<T> event = TestLifecycles.createInstanceLifecycleEvent(getClass(), instanceType);
        TestLifecycles.before(event);
        return event.getInstance();
    }

    protected static void sync() {
        sync(DEFAULT_SLEEP);
    }

    protected static void sync(final long sleep) {
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected <T> T waitOnFuture(Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause != null && cause instanceof RuntimeException) {
                throw RuntimeException.class.cast(cause);
            } else {
                cause = e;
            }
            throw new IllegalStateException(cause);
        }
    }

    public static String getTestSystemProperty(String key) {
        return getTestSystemProperty(key, null);
    }

    public static String getTestSystemProperty(String key, String defaultValue) {
        try {
            String value = readProperties(TCK_PROPERTIES).getProperty(key);
            if (value == null) {
                value = defaultValue;
            }
            return value;
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    protected static Properties readProperties(String name) throws IOException {
        InputStream is = TestBase.class.getClassLoader().getResourceAsStream(name);

        if (is == null) {
            throw new IllegalArgumentException("No such resource: " + name);
        }

        try {
            Properties properties = new Properties();
            properties.load(is);
            return properties;
        } finally {
            try {
                is.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static Key putTempData(TempData data) {
        DatastoreService ds = DatastoreServiceFactory.getDatastoreService();
        Transaction txn = ds.beginTransaction(TransactionOptions.Builder.withXG(true));
        try {
            Class<? extends TempData> type = data.getClass();
            String kind = getKind(type);
            Entity entity = new Entity(kind);
            for (Map.Entry<String, Object> entry : data.toProperties(ds).entrySet()) {
                entity.setProperty(entry.getKey(), entry.getValue());
            }
            data.prePut(ds);
            Key key = ds.put(txn, entity);
            data.postPut(ds);
            txn.commit();
            return key;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (txn.isActive()) {
                txn.rollback();
            }
        }
    }

    public static <T extends TempData> List<T> getAllTempData(Class<T> type) {
        try {
            DatastoreService ds = DatastoreServiceFactory.getDatastoreService();
            String kind = getKind(type);
            PreparedQuery pq = ds.prepare(new Query(kind).addSort("timestamp", Query.SortDirection.ASCENDING));
            Iterator<Entity> iter = pq.asIterator();
            List<T> result = new ArrayList<>();
            while (iter.hasNext()) {
                Entity entity = iter.next();
                T data = type.newInstance();
                data.preGet(ds);
                data.fromProperties(entity.getProperties());
                data.postGet(ds);
                result.add(data);
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static <T extends TempData> T getLastTempData(Class<T> type) {
        try {
            DatastoreService ds = DatastoreServiceFactory.getDatastoreService();
            String kind = getKind(type);
            PreparedQuery pq = ds.prepare(new Query(kind).addSort("timestamp", Query.SortDirection.DESCENDING));
            Iterator<Entity> iter = pq.asIterator();
            if (iter.hasNext()) {
                Entity entity = iter.next();
                T data = type.newInstance();
                data.preGet(ds);
                data.fromProperties(entity.getProperties());
                data.postGet(ds);
                return data;
            } else {
                return null;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void deleteTempData(Class<? extends TempData> type) {
        // check if in-container
        if (isInContainer() == false) {
            return;
        }

        String kind = getKind(type);
        DatastoreService ds = DatastoreServiceFactory.getDatastoreService();

        Transaction txn = ds.beginTransaction(TransactionOptions.Builder.withXG(true));
        try {
            PreparedQuery pq = ds.prepare(txn, new Query(kind));
            for (Entity e : pq.asIterable()) {
                TempData data = type.newInstance();
                data.fromProperties(e.getProperties());
                data.preDelete(ds);
                ds.delete(txn, e.getKey());
                data.postDelete(ds);
            }
            txn.commit();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (txn.isActive()) {
                txn.rollback();
            }
        }
    }

    protected static <T extends TempData> String getKind(Class<T> type) {
        return type.getSimpleName() + readTimestamp(type);
    }

    protected synchronized Long readTimestamp() {
        return readTimestampInternal();
    }

    private Long readTimestampInternal() {
        if (timestamp != null) {
            return (timestamp instanceof Long) ? (Long) timestamp : null;
        }

        timestamp = readTimestampInternal(getClass());

        return readTimestampInternal();
    }

    protected static Long readTimestamp(Class<?> clazz) {
        Object ts = readTimestampInternal(clazz);
        return (ts instanceof Long) ? (Long) ts : null;
    }

    private static Object readTimestampInternal(Class<?> clazz) {
        final InputStream is = clazz.getClassLoader().getResourceAsStream(TIMESTAMP_TXT);
        if (is == null) {
            return new Object(); // marker
        } else {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                String line = reader.readLine();
                return Long.parseLong(line);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } finally {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
