/*
 * The MIT License
 *
 * Copyright 2020 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate;

import com.intuit.karate.core.Engine;
import com.intuit.karate.core.Feature;
import com.intuit.karate.core.FeatureResult;
import com.intuit.karate.core.FeatureRuntime;
import com.intuit.karate.core.HtmlFeatureReport;
import com.intuit.karate.core.HtmlReport;
import com.intuit.karate.core.HtmlSummaryReport;
import com.intuit.karate.core.SyncExecutorService;
import com.intuit.karate.core.Tags;
import com.intuit.karate.http.HttpClientFactory;
import com.intuit.karate.resource.Resource;
import com.intuit.karate.resource.ResourceUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pthomas3
 */
public class Suite implements Runnable {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(Suite.class);

    public final String env;
    public final String tagSelector;
    public final boolean dryRun;
    public final long startTime;
    public final File workingDir;
    public final String buildDir;
    public final String reportDir;
    public final ClassLoader classLoader;
    public final int threadCount;
    public final Semaphore batchLimiter;
    public final List<Feature> features;
    public final Results results;
    public final Collection<RuntimeHook> hooks;
    public final HttpClientFactory clientFactory;
    public final Map<String, String> systemProperties;

    public final boolean outputHtmlReport;
    public final boolean outputCucumberJson;
    public final boolean outputJunitXml;

    public final boolean parallel;
    public final int featureCount;
    public final ExecutorService scenarioExecutor;
    public final ExecutorService pendingTasks;

    public final String karateBase;
    public final String karateConfig;
    public final String karateConfigEnv;

    public final Map<String, Object> SUITE_CACHE = new HashMap();

    private String read(String name) {
        try {
            Resource resource = ResourceUtils.getResource(workingDir, name);
            logger.debug("[config] {}", resource.getPrefixedPath());
            return FileUtils.toString(resource.getStream());
        } catch (Exception e) {
            logger.trace("file not found: {} - {}", name, e.getMessage());
            return null;
        }
    }

    public static Suite forTempUse() {
        return new Suite(Runner.builder().forTempUse());
    }

    public Suite() {
        this(Runner.builder());
    }

    public Suite(Runner.Builder rb) {
        if (rb.forTempUse) {
            dryRun = false;
            outputHtmlReport = false;
            outputCucumberJson = false;
            outputJunitXml = false;
            classLoader = Thread.currentThread().getContextClassLoader();
            clientFactory = HttpClientFactory.DEFAULT;
            startTime = -1;
            env = rb.env;
            systemProperties = null;
            tagSelector = null;
            threadCount = -1;
            batchLimiter = null;
            hooks = null;
            features = null;
            results = null;
            workingDir = FileUtils.WORKING_DIR;
            buildDir = FileUtils.getBuildDir();
            reportDir = null;
            karateBase = null;
            karateConfig = null;
            karateConfigEnv = null;
            parallel = false;
            featureCount = -1;
            scenarioExecutor = null;
            pendingTasks = null;
        } else {
            startTime = System.currentTimeMillis();
            outputHtmlReport = rb.outputHtmlReport;
            outputCucumberJson = rb.outputCucumberJson;
            outputJunitXml = rb.outputJunitXml;            
            rb.resolveAll();
            dryRun = rb.dryRun;
            classLoader = rb.classLoader;
            clientFactory = rb.clientFactory;
            env = rb.env;
            systemProperties = rb.systemProperties;
            tagSelector = Tags.fromKarateOptionsTags(rb.tags);
            threadCount = rb.threadCount;
            batchLimiter = new Semaphore(threadCount);
            hooks = rb.hooks;
            features = rb.features;
            results = new Results(this);
            workingDir = rb.workingDir;
            buildDir = rb.buildDir;
            reportDir = rb.reportDir;
            karateBase = read("classpath:karate-base.js");
            karateConfig = read(rb.configDir + "karate-config.js");
            if (env != null) {
                karateConfigEnv = read(rb.configDir + "karate-config-" + env + ".js");
            } else {
                karateConfigEnv = null;
            }
            featureCount = features.size();
            parallel = threadCount > 1;
            if (parallel) {
                scenarioExecutor = Executors.newFixedThreadPool(threadCount);
                pendingTasks = Executors.newSingleThreadExecutor();
            } else {
                scenarioExecutor = SyncExecutorService.INSTANCE;
                pendingTasks = SyncExecutorService.INSTANCE;
            }
        }
    }

    @Override
    public void run() {
        try {
            int index = 0;
            final List<CompletableFuture> futures = new ArrayList(featureCount);
            for (Feature feature : features) {
                final CompletableFuture future = new CompletableFuture();
                futures.add(future);
                final int featureNum = ++index;
                FeatureRuntime fr = FeatureRuntime.of(this, feature);
                results.addFeatureResult(fr.result);
                fr.setNext(() -> {
                    onFeatureDone(fr, featureNum);
                    future.complete(Boolean.TRUE);
                });
                pendingTasks.submit(fr);
            }
            if (featureCount > 1) {
                logger.debug("waiting for {} features to complete ...", featureCount);
            }
            CompletableFuture[] futuresArray = futures.toArray(new CompletableFuture[futures.size()]);
            CompletableFuture.allOf(futuresArray).join();
            results.setEndTime(System.currentTimeMillis());
            if (outputHtmlReport) {
                HtmlSummaryReport summary = new HtmlSummaryReport();
                for (FeatureResult result : results.getFeatureResults()) {
                    int scenarioCount = result.getScenarioCount();
                    results.addToScenarioCount(scenarioCount);
                    if (scenarioCount != 0) {
                        results.incrementFeatureCount();
                    }
                    results.addToFailCount(result.getFailedCount());
                    results.addToTimeTaken(result.getDurationMillis());
                    if (result.isFailed()) {
                        results.addToFailedList(result.getPackageQualifiedName(), result.getErrorMessages());
                    }
                    if (!result.isEmpty()) {
                        HtmlFeatureReport.saveFeatureResult(reportDir, result);
                        summary.addFeatureResult(result);
                    }
                }
                // saving reports can in rare cases throw errors, so do all this within try-catch
                summary.save(reportDir);
                Engine.saveStatsJson(reportDir, results);
                HtmlReport.saveTimeline(reportDir, results, null);
            }
            results.printStats(threadCount);
            hooks.forEach(h -> h.afterSuite(this));
        } catch (Exception e) {
            logger.error("runner failed: " + e);
            results.setFailureReason(e);
        } finally {
            scenarioExecutor.shutdownNow();
            pendingTasks.shutdownNow();
        }
    }

    private void onFeatureDone(FeatureRuntime fr, int index) {
        FeatureResult result = fr.result;
        Feature feature = fr.feature;
        if (result.getScenarioCount() > 0) { // possible that zero scenarios matched tags
            try { // edge case that reports are not writable
                if (outputCucumberJson) {
                    Engine.saveResultJson(reportDir, result, null);
                }
                if (outputJunitXml) {
                    Engine.saveResultXml(reportDir, result, null);
                }
                String status = result.isFailed() ? "fail" : "pass";
                logger.info("<<{}>> feature {} of {}: {}", status, index, featureCount, feature);
                result.printStats(null);
            } catch (Exception e) {
                logger.error("<<error>> unable to write report file(s): {} - {}", feature, e + "");
                result.printStats(null);
            }
        } else {
            results.addToSkipCount(1);
            if (logger.isTraceEnabled()) {
                logger.trace("<<skip>> feature {} of {}: {}", index, featureCount, feature);
            }
        }
    }

}
