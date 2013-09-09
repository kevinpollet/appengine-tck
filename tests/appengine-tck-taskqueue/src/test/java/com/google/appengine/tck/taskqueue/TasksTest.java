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

package com.google.appengine.tck.taskqueue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;

import com.google.appengine.api.taskqueue.InvalidQueueModeException;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.RetryOptions;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.tck.taskqueue.support.DefaultQueueServlet;
import com.google.appengine.tck.taskqueue.support.PrintServlet;
import com.google.appengine.tck.taskqueue.support.RequestData;
import com.google.appengine.tck.taskqueue.support.RetryTestServlet;
import com.google.appengine.tck.taskqueue.support.TestQueueServlet;
import org.jboss.arquillian.junit.Arquillian;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withHeader;
import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withMethod;
import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withPayload;
import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withTag;
import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withTaskName;
import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withUrl;
import static com.google.appengine.api.taskqueue.TaskOptions.Method.DELETE;
import static com.google.appengine.api.taskqueue.TaskOptions.Method.GET;
import static com.google.appengine.api.taskqueue.TaskOptions.Method.HEAD;
import static com.google.appengine.api.taskqueue.TaskOptions.Method.POST;
import static com.google.appengine.api.taskqueue.TaskOptions.Method.PULL;
import static com.google.appengine.api.taskqueue.TaskOptions.Method.PUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 * @author <a href="mailto:mluksa@redhat.com">Marko Luksa</a>
 */
@RunWith(Arquillian.class)
public class TasksTest extends QueueTestBase {

    @Before
    public void setUp() throws Exception {
        DefaultQueueServlet.reset();
        TestQueueServlet.reset();
        RetryTestServlet.reset();

        purgeAndPause(QueueFactory.getQueue("pull-queue"), QueueFactory.getQueue("tasks-queue"),
                QueueFactory.getQueue("test"), QueueFactory.getDefaultQueue());
    }

    @After
    public void tearDown() throws Exception {
        PrintServlet.reset();

        purgeAndPause(QueueFactory.getQueue("pull-queue"), QueueFactory.getQueue("tasks-queue"),
                QueueFactory.getQueue("test"), QueueFactory.getDefaultQueue());
    }

    @Test
    public void testSmoke() throws Exception {
        final Queue queue = QueueFactory.getQueue("tasks-queue");
        Assert.assertEquals("tasks-queue", queue.getQueueName());

        queue.add(withUrl(URL));
        sync();
        assertNotNull(PrintServlet.getLastRequest());
    }

    @Test
    public void testTaskWithoutUrlIsSubmittedToDefaultUrl() throws Exception {
        Queue defaultQueue = QueueFactory.getDefaultQueue();
        defaultQueue.add(withMethod(POST));
        sync();
        assertTrue("DefaultQueueServlet was not invoked", DefaultQueueServlet.wasInvoked());

        Queue testQueue = QueueFactory.getQueue("test");
        testQueue.add(withMethod(POST));
        sync();
        assertTrue("TestQueueServlet was not invoked", TestQueueServlet.wasInvoked());
    }

    @Test
    public void testTaskHandleContainsAllNecessaryProperties() throws Exception {
        String name = "testTaskHandleContainsAllNecessaryProperties-" + System.currentTimeMillis();
        Queue queue = QueueFactory.getDefaultQueue();

        TaskOptions options = withTaskName(name).payload("payload");
        options.etaMillis(0); // TODO -- remove this once NPE is fixewd

        TaskHandle handle = queue.add(options);

        assertEquals("default", handle.getQueueName());
        assertEquals(name, handle.getName());
        assertEquals("payload", new String(handle.getPayload(), "UTF-8"));
        assertNotNull(handle.getEtaMillis());
        assertEquals(0, (int) handle.getRetryCount());
    }

    @Test
    public void testTaskHandleContainsAutoGeneratedTaskNameWhenTaskNameNotDefinedInTaskOptions() throws Exception {
        Queue queue = QueueFactory.getDefaultQueue();
        TaskHandle handle = queue.add();
        assertNotNull(handle.getName());
    }

    @Test
    public void testRequestHeaders() throws Exception {
        String name = "testRequestHeaders-1-" + System.currentTimeMillis();
        Queue defaultQueue = QueueFactory.getDefaultQueue();
        defaultQueue.add(withTaskName(name));
        sync();

        RequestData request = DefaultQueueServlet.getLastRequest();
        assertEquals("default", request.getHeader(QUEUE_NAME));
        assertEquals(name, request.getHeader(TASK_NAME));
        assertNotNull(request.getHeader(TASK_RETRY_COUNT));
        assertNotNull(request.getHeader(TASK_EXECUTION_COUNT));
        assertNotNull(request.getHeader(TASK_ETA));

        String name2 = "testRequestHeaders-2-" + System.currentTimeMillis();
        Queue testQueue = QueueFactory.getQueue("test");
        testQueue.add(withTaskName(name2));
        sync();

        request = TestQueueServlet.getLastRequest();
        assertEquals("test", request.getHeader(QUEUE_NAME));
        assertEquals(name2, request.getHeader(TASK_NAME));
    }

    @Test
    public void testAllPushMethodsAreSupported() throws Exception {
        assertServletReceivesCorrectMethod(GET);
        assertServletReceivesCorrectMethod(PUT);
        assertServletReceivesCorrectMethod(HEAD);
        assertServletReceivesCorrectMethod(POST);
        assertServletReceivesCorrectMethod(DELETE);
    }

    private void assertServletReceivesCorrectMethod(TaskOptions.Method method) {
        MethodRequestHandler handler = new MethodRequestHandler();
        PrintServlet.setRequestHandler(handler);

        Queue queue = QueueFactory.getQueue("tasks-queue");
        queue.add(withUrl(URL).method(method));
        sync();

        assertEquals("Servlet received invalid HTTP method.", method.name(), handler.method);
    }

    @Test
    public void testPayload() throws Exception {
        String sentPayload = "payload";

        Queue queue = QueueFactory.getDefaultQueue();
        queue.add(withPayload(sentPayload));
        sync();

        String receivedPayload = new String(DefaultQueueServlet.getLastRequest().getBody(), "UTF-8");
        assertEquals(sentPayload, receivedPayload);
    }

    @Test
    public void testHeaders() throws Exception {
        Queue queue = QueueFactory.getDefaultQueue();
        queue.add(withHeader("header_key", "header_value"));
        sync();

        RequestData lastRequest = DefaultQueueServlet.getLastRequest();
        assertEquals("header_value", lastRequest.getHeader("header_key"));
    }

    @Test
    public void testParams() throws Exception {
        class ParamHandler implements PrintServlet.RequestHandler {
            private String paramValue;

            public void handleRequest(ServletRequest req) {
                paramValue = req.getParameter("single_value");
            }
        }

        ParamHandler handler = new ParamHandler();
        PrintServlet.setRequestHandler(handler);

        final Queue queue = QueueFactory.getQueue("tasks-queue");
        queue.add(withUrl(URL).param("single_value", "param_value"));
        sync();

        assertEquals("param_value", handler.paramValue);
    }

    @Test
    public void testMultiValueParams() throws Exception {
        class ParamHandler implements PrintServlet.RequestHandler {
            private String[] paramValues;

            public void handleRequest(ServletRequest req) {
                paramValues = req.getParameterValues("multi_value");
            }
        }

        ParamHandler handler = new ParamHandler();
        PrintServlet.setRequestHandler(handler);

        final Queue queue = QueueFactory.getQueue("tasks-queue");
        queue.add(
            withUrl(URL)
                .param("multi_value", "param_value1")
                .param("multi_value", "param_value2"));
        sync();

        assertNotNull(handler.paramValues);
        assertEquals(
            new HashSet<>(Arrays.asList("param_value1", "param_value2")),
            new HashSet<>(Arrays.asList(handler.paramValues)));
    }

    @Test
    public void testRetry() throws Exception {
        long numTimesToFail = 1;
        String key = "testRetry-" + System.currentTimeMillis();

        Queue queue = QueueFactory.getDefaultQueue();
        queue.add(withUrl("/_ah/retryTest")
            .param("testdata-key", key)
            .param("times-to-fail", String.valueOf(numTimesToFail))
            .retryOptions(RetryOptions.Builder.withTaskRetryLimit(5)));

        String countKey = RetryTestServlet.getInvocationCountKey(key);
        long expectedAttempts = (numTimesToFail + 1);
        long actualAttempts = waitForTestData(countKey, expectedAttempts);
        assertEquals(expectedAttempts, actualAttempts);

        String requestKey1 = RetryTestServlet.getRequestDataKey(key, 1);
        RequestData request1 = waitForTestDataToExist(requestKey1);
        assertEquals("0", request1.getHeader(TASK_RETRY_COUNT));
        assertEquals("0", request1.getHeader(TASK_EXECUTION_COUNT));

        String requestKey2 = RetryTestServlet.getRequestDataKey(key, 2);
        RequestData request2 = waitForTestDataToExist(requestKey2);
        assertEquals("1", request2.getHeader(TASK_RETRY_COUNT));
        assertEquals("1", request2.getHeader(TASK_EXECUTION_COUNT));
    }

    @Test
    public void testRetryLimitIsHonored() throws Exception {
        long numTimesToFail = 10;
        int retryLimit = 2;
        String key = "testRetryLimitIsHonored-" + System.currentTimeMillis();

        Queue queue = QueueFactory.getDefaultQueue();
        queue.add(withUrl("/_ah/retryTest")
            .param("testdata-key", key)
            .param("times-to-fail", String.valueOf(numTimesToFail))
            .retryOptions(RetryOptions.Builder.withTaskRetryLimit(retryLimit)));

        long expectedAttempts = (long) (retryLimit + 1);
        String countKey = RetryTestServlet.getInvocationCountKey(key);
        Long actualAttempts = waitForTestData(countKey, expectedAttempts);

        // Ideally this would be the assert, but when a task fails with 500, the test framework
        // cannot capture it for the attempt count.
        // assertEquals(expectedAttempts, actualAttempts);

        // Allow room for one task to fail with 500.
        assertTrue("Task retries lower than specified via withTaskRetryLimit()",
            actualAttempts == expectedAttempts || actualAttempts == expectedAttempts - 1);
    }

    @Test(expected = InvalidQueueModeException.class)
    public void testLeaseTaskFromPushQueueThrowsException() {
        Queue pushQueue = QueueFactory.getDefaultQueue();
        pushQueue.leaseTasks(1000, TimeUnit.SECONDS, 1);
    }

    @Test
    public void testOnlyPullTasksCanBeAddedToPullQueue() {
        Queue pullQueue = QueueFactory.getQueue("pull-queue");
        pullQueue.add(withMethod(PULL));
        assertAddThrowsExceptionForMethod(DELETE, pullQueue);
        assertAddThrowsExceptionForMethod(GET, pullQueue);
        assertAddThrowsExceptionForMethod(HEAD, pullQueue);
        assertAddThrowsExceptionForMethod(PUT, pullQueue);
        assertAddThrowsExceptionForMethod(POST, pullQueue);
    }

    @Test
    public void testPullTasksCannotBeAddedToPushQueue() {
        Queue pushQueue = QueueFactory.getDefaultQueue();
        pushQueue.add(withMethod(DELETE));
        pushQueue.add(withMethod(GET));
        pushQueue.add(withMethod(HEAD));
        pushQueue.add(withMethod(PUT));
        pushQueue.add(withMethod(POST));
        assertAddThrowsExceptionForMethod(PULL, pushQueue);
    }

    @Test
    public void testOnlyPullTasksCanHaveTag() {
        Queue pullQueue = QueueFactory.getQueue("pull-queue");
        pullQueue.add(withMethod(PULL).tag("foo"));

        Queue pushQueue = QueueFactory.getDefaultQueue();
        try {
            pushQueue.add(withTag("foo"));
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // pass
        }
    }

    private void assertAddThrowsExceptionForMethod(TaskOptions.Method method, Queue queue) {
        try {
            queue.add(withMethod(method));
            fail("Expected InvalidQueueModeException");
        } catch (InvalidQueueModeException e) {
            // pass
        }
    }

    private class MethodRequestHandler implements PrintServlet.RequestHandler {
        private String method;

        public void handleRequest(ServletRequest req) {
            method = ((HttpServletRequest) req).getMethod();
        }
    }

}
