/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.job;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.AbstractKylinTestCase;
import org.apache.kylin.common.util.ClassUtil;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.cube.CubeUpdate;
import org.apache.kylin.engine.EngineFactory;
import org.apache.kylin.engine.mr.CubingJob;
import org.apache.kylin.job.engine.JobEngineConfig;
import org.apache.kylin.job.execution.AbstractExecutable;
import org.apache.kylin.job.execution.DefaultChainedExecutable;
import org.apache.kylin.job.execution.ExecutableState;
import org.apache.kylin.job.impl.threadpool.DefaultScheduler;
import org.apache.kylin.job.manager.ExecutableManager;
import org.apache.kylin.storage.hbase.steps.HBaseMetadataTestCase;
import org.apache.kylin.storage.hbase.util.ZookeeperJobLock;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.Lists;

public class BuildCubeWithEngineTest {

    private CubeManager cubeManager;
    private DefaultScheduler scheduler;
    protected ExecutableManager jobService;

    private static final Log logger = LogFactory.getLog(BuildCubeWithEngineTest.class);

    protected void waitForJob(String jobId) {
        while (true) {
            AbstractExecutable job = jobService.getJob(jobId);
            if (job.getStatus() == ExecutableState.SUCCEED || job.getStatus() == ExecutableState.ERROR) {
                break;
            } else {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        logger.info("Adding to classpath: " + new File(HBaseMetadataTestCase.SANDBOX_TEST_DATA).getAbsolutePath());
        ClassUtil.addClasspath(new File(HBaseMetadataTestCase.SANDBOX_TEST_DATA).getAbsolutePath());
        System.setProperty(KylinConfig.KYLIN_CONF, "../examples/test_case_data/sandbox");
        if (System.getProperty("hdp.version") == null) {
            throw new RuntimeException("No hdp.version set; Please set hdp.version in your jvm option, for example: -Dhdp.version=2.2.4.2-2");
        }

    }

    @Before
    public void before() throws Exception {
        HBaseMetadataTestCase.staticCreateTestMetadata(AbstractKylinTestCase.SANDBOX_TEST_DATA);

        DeployUtil.initCliWorkDir();
        DeployUtil.deployMetadata();
        DeployUtil.overrideJobJarLocations();

        final KylinConfig kylinConfig = KylinConfig.getInstanceFromEnv();
        jobService = ExecutableManager.getInstance(kylinConfig);
        scheduler = DefaultScheduler.getInstance();
        scheduler.init(new JobEngineConfig(kylinConfig), new ZookeeperJobLock());
        if (!scheduler.hasStarted()) {
            throw new RuntimeException("scheduler has not been started");
        }
        cubeManager = CubeManager.getInstance(kylinConfig);
        for (String jobId : jobService.getAllJobIds()) {
            if (jobService.getJob(jobId) instanceof CubingJob) {
                jobService.deleteJob(jobId);
            }
        }

    }

    @After
    public void after() {
        HBaseMetadataTestCase.staticCleanupTestMetadata();
    }

    @Test
    public void test() throws Exception {
        DeployUtil.prepareTestDataForNormalCubes("test_kylin_cube_with_slr_left_join_empty");
        testInner();
        testLeft();
    }

    private void testInner() throws Exception {
        String[] testCase = new String[] { "testInnerJoinCube", "testInnerJoinCube2" };
        runTestAndAssertSucceed(testCase);
    }

    private void testLeft() throws Exception {
        String[] testCase = new String[] { "testLeftJoinCube", "testLeftJoinCube2" };
        runTestAndAssertSucceed(testCase);
    }

    private void runTestAndAssertSucceed(String[] testCase) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(testCase.length);
        final CountDownLatch countDownLatch = new CountDownLatch(testCase.length);
        List<Future<List<String>>> tasks = Lists.newArrayListWithExpectedSize(testCase.length);
        for (int i = 0; i < testCase.length; i++) {
            tasks.add(executorService.submit(new TestCallable(testCase[i], countDownLatch)));
        }
        countDownLatch.await();
        try {
            for (int i = 0; i < tasks.size(); ++i) {
                Future<List<String>> task = tasks.get(i);
                final List<String> jobIds = task.get();
                for (String jobId : jobIds) {
                    assertJobSucceed(jobId);
                }
            }
        } catch (Exception ex) {
            logger.error(ex);
            throw ex;
        }
    }

    private void assertJobSucceed(String jobId) {
        assertEquals("The job '" + jobId + "' is failed.", ExecutableState.SUCCEED, jobService.getOutput(jobId).getState());
    }

    private class TestCallable implements Callable<List<String>> {

        private final String methodName;
        private final CountDownLatch countDownLatch;

        public TestCallable(String methodName, CountDownLatch countDownLatch) {
            this.methodName = methodName;
            this.countDownLatch = countDownLatch;
        }

        @SuppressWarnings("unchecked")
        @Override
        public List<String> call() throws Exception {
            try {
                final Method method = BuildCubeWithEngineTest.class.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return (List<String>) method.invoke(BuildCubeWithEngineTest.this);
            } finally {
                countDownLatch.countDown();
            }
        }
    }

    @SuppressWarnings("unused")
    // called by reflection
    private List<String> testInnerJoinCube2() throws Exception {
        clearSegment("test_kylin_cube_with_slr_empty");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        long date1 = 0;
        long date2 = f.parse("2013-01-01").getTime();
        long date3 = f.parse("2022-01-01").getTime();
        List<String> result = Lists.newArrayList();
        result.add(buildSegment("test_kylin_cube_with_slr_empty", date1, date2));
        result.add(buildSegment("test_kylin_cube_with_slr_empty", date2, date3));
        return result;
    }

    @SuppressWarnings("unused")
    // called by reflection
    private List<String> testInnerJoinCube() throws Exception {
        clearSegment("test_kylin_cube_without_slr_empty");

        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        f.setTimeZone(TimeZone.getTimeZone("GMT"));

        // this cube's start date is 0, end date is 20501112000000
        long date1 = 0;
        long date2 = f.parse("2050-01-11").getTime();

        // this cube doesn't support incremental build, always do full build

        List<String> result = Lists.newArrayList();
        result.add(buildSegment("test_kylin_cube_without_slr_empty", date1, date2));
        return result;
    }
    
    @SuppressWarnings("unused")
    // called by reflection
    private List<String> testLeftJoinCube2() throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        List<String> result = Lists.newArrayList();
        final String cubeName = "test_kylin_cube_without_slr_left_join_empty";
        // this cube's start date is 0, end date is 20120601000000
        long dateStart = cubeManager.getCube(cubeName).getDescriptor().getModel().getPartitionDesc().getPartitionDateStart();
        long dateEnd = f.parse("2012-06-01").getTime();

        clearSegment(cubeName);
        result.add(buildSegment(cubeName, dateStart, dateEnd));

        // then submit an append job, start date is 20120601000000, end
        // date is 20220101000000
        dateStart = f.parse("2012-06-01").getTime();
        dateEnd = f.parse("2022-01-01").getTime();
        result.add(buildSegment(cubeName, dateStart, dateEnd));

        // build an empty segment which doesn't have data
        dateStart = f.parse("2022-01-01").getTime();
        dateEnd = f.parse("2023-01-01").getTime();
        result.add(buildSegment(cubeName, dateStart, dateEnd));

        return result;

    }

    @SuppressWarnings("unused")
    // called by reflection
    private List<String> testLeftJoinCube() throws Exception {
        String cubeName = "test_kylin_cube_with_slr_left_join_empty";
        clearSegment(cubeName);

        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        long dateStart = cubeManager.getCube(cubeName).getDescriptor().getModel().getPartitionDesc().getPartitionDateStart();
        long dateEnd = f.parse("2050-11-12").getTime();

        // this cube's start date is 0, end date is 20501112000000
        List<String> result = Lists.newArrayList();
        result.add(buildSegment(cubeName, dateStart, dateEnd));
        return result;

    }

    private void clearSegment(String cubeName) throws Exception {
        CubeInstance cube = cubeManager.getCube(cubeName);
        // remove all existing segments
        CubeUpdate cubeBuilder = new CubeUpdate(cube);
        cubeBuilder.setToRemoveSegs(cube.getSegments().toArray(new CubeSegment[cube.getSegments().size()]));
        cubeManager.updateCube(cubeBuilder);
    }

    private String buildSegment(String cubeName, long startDate, long endDate) throws Exception {
        CubeSegment segment = cubeManager.appendSegments(cubeManager.getCube(cubeName), endDate);
        DefaultChainedExecutable job = EngineFactory.createBatchCubingJob(segment, "TEST");
        jobService.addJob(job);
        waitForJob(job.getId());
        return job.getId();
    }

}