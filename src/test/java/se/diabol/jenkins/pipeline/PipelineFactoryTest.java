/*
This file is part of Delivery Pipeline Plugin.

Delivery Pipeline Plugin is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Delivery Pipeline Plugin is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Delivery Pipeline Plugin.
If not, see <http://www.gnu.org/licenses/>.
*/
package se.diabol.jenkins.pipeline;

import au.com.centrumsystems.hudson.plugin.buildpipeline.BuildPipelineView;
import au.com.centrumsystems.hudson.plugin.buildpipeline.DownstreamProjectGridBuilder;
import au.com.centrumsystems.hudson.plugin.buildpipeline.trigger.BuildPipelineTrigger;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.BuildTrigger;
import hudson.util.OneShotEvent;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.*;
import se.diabol.jenkins.pipeline.model.Pipeline;
import se.diabol.jenkins.pipeline.model.Stage;
import se.diabol.jenkins.pipeline.model.Task;
import se.diabol.jenkins.pipeline.model.status.Status;

import java.io.IOException;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static se.diabol.jenkins.pipeline.model.status.StatusFactory.idle;

public class PipelineFactoryTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void testExtractPipeline() throws Exception {
        FreeStyleProject compile = jenkins.createFreeStyleProject("comp");
        FreeStyleProject deploy = jenkins.createFreeStyleProject("deploy");
        FreeStyleProject test = jenkins.createFreeStyleProject("test");

        compile.addProperty(new PipelineProperty("Compile", "Build"));
        compile.save();

        deploy.addProperty(new PipelineProperty("Deploy", "Deploy"));
        deploy.save();
        test.addProperty(new PipelineProperty("Test", "Test"));
        test.save();

        compile.getPublishersList().add(new BuildTrigger("test", false));
        test.getPublishersList().add(new BuildTrigger("deploy", false));

        jenkins.getInstance().rebuildDependencyGraph();


        Pipeline pipeline = PipelineFactory.extractPipeline("Piper", compile);

        assertEquals(pipeline,
                new Pipeline("Piper", null, null, null,
                        asList(new Stage("Build", asList(new Task("comp", "Compile", null, idle(), "", false, null))),
                                new Stage("Test", asList(new Task("test", "Test", null, idle(), "", false, null))),
                                new Stage("Deploy", asList(new Task("deploy", "Deploy", null, idle(), "", false, null)))), false));


    }

    @Test
    public void testExtractSimpleForkJoinPipeline() throws Exception {
        FreeStyleProject build = jenkins.createFreeStyleProject("build");
        build.addProperty(new PipelineProperty(null, "build"));
        FreeStyleProject deploy1 = jenkins.createFreeStyleProject("deploy1");
        deploy1.addProperty(new PipelineProperty(null, "CI"));
        FreeStyleProject deploy2 = jenkins.createFreeStyleProject("deploy2");
        deploy2.addProperty(new PipelineProperty(null, "CI"));
        FreeStyleProject deploy3 = jenkins.createFreeStyleProject("deploy3");
        deploy3.addProperty(new PipelineProperty(null, "QA"));

        build.getPublishersList().add(new BuildTrigger("deploy1,deploy2", false));
        deploy1.getPublishersList().add(new BuildTrigger("deploy3", false));
        deploy2.getPublishersList().add(new BuildTrigger("deploy3", false));

        jenkins.getInstance().rebuildDependencyGraph();

        Pipeline pipeline = PipelineFactory.extractPipeline("Pipeline", build);

        assertEquals(3, pipeline.getStages().size());
        assertEquals(1, pipeline.getStages().get(2).getTasks().size());
        assertEquals("deploy3", pipeline.getStages().get(2).getTasks().get(0).getName());



    }


    @Test
    public void testCreatePipelineAggregatedSharedTask() throws Exception {
        FreeStyleProject build1 = jenkins.createFreeStyleProject("build1");
        FreeStyleProject build2 = jenkins.createFreeStyleProject("build2");
        FreeStyleProject sonar = jenkins.createFreeStyleProject("sonar1");
        FreeStyleProject test = jenkins.createFreeStyleProject("test");
        jenkins.createFreeStyleProject("prod");
        build1.getPublishersList().add(new BuildTrigger("sonar1,test", true));
        build2.getPublishersList().add(new BuildTrigger("sonar1", true));
        test.getPublishersList().add(new BuildPipelineTrigger("prod", null));

        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.setQuietPeriod(0);
        final Pipeline pipe1 = PipelineFactory.extractPipeline("pipe1", build1);
        final Pipeline pipe2 = PipelineFactory.extractPipeline("pipe2", build2);

        Pipeline aggregated1 = PipelineFactory.createPipelineAggregated(pipe1);
        Pipeline aggregated2 = PipelineFactory.createPipelineAggregated(pipe2);

        assertNull(aggregated1.getStages().get(0).getVersion());
        assertNull(aggregated2.getStages().get(0).getVersion());

        assertTrue(aggregated1.getStages().get(0).getTasks().get(0).getStatus().isIdle());
        assertTrue(aggregated2.getStages().get(0).getTasks().get(0).getStatus().isIdle());

        assertTrue(aggregated1.getStages().get(3).getTasks().get(0).getStatus().isIdle());

        assertEquals("job/sonar1/", aggregated1.getStages().get(1).getTasks().get(0).getLink());

        jenkins.buildAndAssertSuccess(build1);
        jenkins.waitUntilNoActivity();
        assertNotNull(sonar.getLastBuild());

        assertEquals(4, pipe1.getStages().size());
        assertEquals(2, pipe2.getStages().size());
        assertNotNull(sonar.getBuild("1"));

        aggregated1 = PipelineFactory.createPipelineAggregated(pipe1);
        aggregated2 = PipelineFactory.createPipelineAggregated(pipe2);

        assertEquals("#1", aggregated1.getStages().get(1).getVersion());
        assertEquals("job/sonar1/1/", aggregated1.getStages().get(1).getTasks().get(0).getLink());
        assertEquals("1", aggregated1.getStages().get(1).getTasks().get(0).getBuildId());

        assertTrue(aggregated1.getStages().get(2).getTasks().get(0).getStatus().isSuccess());

        assertEquals(true, aggregated2.getStages().get(1).getTasks().get(0).getStatus().isIdle());
        assertEquals("job/sonar1/", aggregated2.getStages().get(1).getTasks().get(0).getLink());
        assertNull(aggregated2.getStages().get(1).getTasks().get(0).getBuildId());


        assertTrue(aggregated1.getStages().get(3).getTasks().get(0).getStatus().isIdle());

        jenkins.buildAndAssertSuccess(build2);
        jenkins.waitUntilNoActivity();

        aggregated1 = PipelineFactory.createPipelineAggregated(pipe1);
        aggregated2 = PipelineFactory.createPipelineAggregated(pipe2);

        assertEquals("#1", aggregated1.getStages().get(1).getVersion());
        assertEquals("#1", aggregated2.getStages().get(1).getVersion());

        assertEquals(true, aggregated2.getStages().get(1).getTasks().get(0).getStatus().isSuccess());
        assertEquals("job/sonar1/2/", aggregated2.getStages().get(1).getTasks().get(0).getLink());
        assertEquals("2", aggregated2.getStages().get(1).getTasks().get(0).getBuildId());

        jenkins.buildAndAssertSuccess(build1);
        jenkins.waitUntilNoActivity();

        aggregated1 = PipelineFactory.createPipelineAggregated(pipe1);
        aggregated2 = PipelineFactory.createPipelineAggregated(pipe2);


        assertEquals("#2", aggregated1.getStages().get(1).getVersion());
        assertEquals("#1", aggregated2.getStages().get(1).getVersion());

        assertEquals("job/sonar1/3/", aggregated1.getStages().get(1).getTasks().get(0).getLink());
        assertEquals("3", aggregated1.getStages().get(1).getTasks().get(0).getBuildId());

        assertEquals("job/sonar1/2/", aggregated2.getStages().get(1).getTasks().get(0).getLink());
        assertEquals("2", aggregated2.getStages().get(1).getTasks().get(0).getBuildId());


        assertTrue(aggregated1.getStages().get(3).getTasks().get(0).getStatus().isIdle());

        jenkins.buildAndAssertSuccess(build1);
        jenkins.waitUntilNoActivity();
        assertTrue(aggregated1.getStages().get(2).getTasks().get(0).getStatus().isSuccess());
        assertEquals("#2", aggregated1.getStages().get(2).getVersion());
        assertTrue(aggregated1.getStages().get(3).getTasks().get(0).getStatus().isIdle());


        BuildPipelineView view = new BuildPipelineView("", "", new DownstreamProjectGridBuilder("build1"), "1", false, null);
        view.triggerManualBuild(1, "prod", "test");
        jenkins.waitUntilNoActivity();
        aggregated1 = PipelineFactory.createPipelineAggregated(pipe1);
        assertTrue(aggregated1.getStages().get(3).getTasks().get(0).getStatus().isSuccess());
        assertEquals("#1", aggregated1.getStages().get(3).getVersion());


    }


    @Test
    public void testAggregatedStageWithTwoManualTasks() throws Exception {
        FreeStyleProject build = jenkins.createFreeStyleProject("build");
        FreeStyleProject ci1 = jenkins.createFreeStyleProject("ci1");
        FreeStyleProject ci2 = jenkins.createFreeStyleProject("ci2");
        ci1.addProperty(new PipelineProperty("ci1", "CI1"));
        ci2.addProperty(new PipelineProperty("ci2", "CI1"));
        build.getPublishersList().add(new BuildPipelineTrigger("ci1", null));
        build.getPublishersList().add(new BuildPipelineTrigger("ci2", null));
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.setQuietPeriod(0);
        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();

        assertNotNull(build.getLastBuild());

        BuildPipelineView view = new BuildPipelineView("", "", new DownstreamProjectGridBuilder("build"), "1", false, null);
        view.triggerManualBuild(1, "ci1", "build");

        jenkins.waitUntilNoActivity();
        assertNotNull(ci1.getLastBuild());
        assertNull(ci2.getLastBuild());

        Pipeline pipeline = PipelineFactory.extractPipeline("test", build);
        Pipeline aggregated = PipelineFactory.createPipelineAggregated(pipeline);
        assertNotNull(aggregated);
        assertEquals("ci1", aggregated.getStages().get(1).getTasks().get(0).getName());
        assertEquals("ci2", aggregated.getStages().get(1).getTasks().get(1).getName());
        assertEquals("SUCCESS", aggregated.getStages().get(1).getTasks().get(0).getStatus().toString());
        assertEquals("IDLE", aggregated.getStages().get(1).getTasks().get(1).getStatus().toString());
        assertEquals("#1", aggregated.getStages().get(1).getVersion());

        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();

        aggregated = PipelineFactory.createPipelineAggregated(pipeline);
        assertNotNull(aggregated);
        assertEquals("#2", build.getLastBuild().getDisplayName());
        assertEquals("SUCCESS", aggregated.getStages().get(1).getTasks().get(0).getStatus().toString());
        assertEquals("IDLE", aggregated.getStages().get(1).getTasks().get(1).getStatus().toString());
        assertEquals("#1", aggregated.getStages().get(1).getVersion());

        view.triggerManualBuild(2, "ci2", "build");
        jenkins.waitUntilNoActivity();
        aggregated = PipelineFactory.createPipelineAggregated(pipeline);
        assertNotNull(aggregated);
        assertEquals("IDLE", aggregated.getStages().get(1).getTasks().get(0).getStatus().toString());
        assertEquals("SUCCESS", aggregated.getStages().get(1).getTasks().get(1).getStatus().toString());
        assertEquals("#2", aggregated.getStages().get(1).getVersion());







    }

    @Test
    public void testCreatePipelineLatest() throws Exception {
        FreeStyleProject build = jenkins.createFreeStyleProject("build");
        build.addProperty(new PipelineProperty("", "Build"));
        FreeStyleProject sonar = jenkins.createFreeStyleProject("sonar");
        sonar.addProperty(new PipelineProperty("Sonar", "Build"));
        FreeStyleProject deploy = jenkins.createFreeStyleProject("deploy");
        deploy.addProperty(new PipelineProperty("Deploy", "CI"));
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.setQuietPeriod(0);

        assertEquals(new Pipeline("Pipeline", null, null, null, asList(new Stage("Build", asList(new Task("build", "build", null, idle(), null,false, null)))), false), PipelineFactory.extractPipeline("Pipeline", build));


        build.getPublishersList().add(new BuildTrigger("sonar,deploy", false));
        jenkins.getInstance().rebuildDependencyGraph();

        Pipeline pipeline = PipelineFactory.extractPipeline("Pipeline", build);

        assertEquals(new Pipeline("Pipeline", null, null, null, asList(new Stage("Build", asList(new Task("build", "build", null, idle(), null, false, null), new Task("sonar", "Sonar",null, idle(), null, false, null))), new Stage("CI", asList(new Task("deploy", "Deploy", null, idle(), null, false, null)))), false), pipeline);
        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();

        Pipeline latest = PipelineFactory.createPipelineLatest(pipeline);

        assertNotNull(latest);

        assertTrue(latest.getStages().get(0).getTasks().get(0).getStatus().isSuccess());
        assertTrue(latest.getStages().get(0).getTasks().get(1).getStatus().isSuccess());
        assertTrue(latest.getStages().get(1).getTasks().get(0).getStatus().isSuccess());
        assertEquals("job/build/1/", latest.getStages().get(0).getTasks().get(0).getLink());
    }


    @Test
    public void testPipelineLatestDownstreamIsDisabled() throws Exception {
        FreeStyleProject build = jenkins.createFreeStyleProject("build");
        FreeStyleProject disabled = jenkins.createFreeStyleProject("disabled");
        disabled.makeDisabled(true);
        build.getPublishersList().add(new BuildTrigger("disabled", false));
        jenkins.getInstance().rebuildDependencyGraph();
        jenkins.buildAndAssertSuccess(build);
        jenkins.waitUntilNoActivity();
        Pipeline pipeline = PipelineFactory.extractPipeline("Pipeline", build);
        Pipeline latest = PipelineFactory.createPipelineLatest(pipeline);
        assertNotNull(latest);
        assertEquals(2, latest.getStages().size());
        assertEquals("SUCCESS", latest.getStages().get(0).getTasks().get(0).getStatus().toString());
        assertEquals("DISABLED", latest.getStages().get(1).getTasks().get(0).getStatus().toString());


    }

    @Test
    public void testResolveStatusIdle() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        Status status = PipelineFactory.resolveStatus(project, null);
        assertTrue(status.isIdle());
        assertEquals("IDLE", status.toString());
        assertEquals(-1, status.getLastActivity());
        assertEquals(-1, status.getDuration());

    }

    @Test
    public void testResolveStatusDisabled() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.makeDisabled(true);
        Status status = PipelineFactory.resolveStatus(project, null);
        assertTrue(status.isDisabled());
        assertEquals("DISABLED", status.toString());
        assertEquals(-1, status.getLastActivity());
        assertEquals(-1, status.getDuration());

    }

    @Test
    public void testResolveStatusSuccess() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        jenkins.buildAndAssertSuccess(project);
        jenkins.waitUntilNoActivity();
        Status status = PipelineFactory.resolveStatus(project, project.getLastBuild());
        assertTrue(status.isSuccess());
        assertEquals("SUCCESS", status.toString());
        assertEquals(project.getLastBuild().getTimeInMillis(), status.getLastActivity());
        assertEquals(project.getLastBuild().getDuration(), status.getDuration());

    }

    @Test
    public void testResolveStatusFailure() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new FailureBuilder());
        project.scheduleBuild2(0);
        jenkins.waitUntilNoActivity();
        Status status = PipelineFactory.resolveStatus(project, project.getLastBuild());
        assertTrue(status.isFailed());
        assertEquals("FAILED", status.toString());
        assertEquals(project.getLastBuild().getTimeInMillis(), status.getLastActivity());
        assertEquals(project.getLastBuild().getDuration(), status.getDuration());
    }


    @Test
    public void testResolveStatusUnstable() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new UnstableBuilder());
        project.scheduleBuild2(0);
        jenkins.waitUntilNoActivity();
        Status status = PipelineFactory.resolveStatus(project, project.getLastBuild());
        assertTrue(status.isUnstable());
        assertEquals("UNSTABLE", status.toString());
        assertEquals(project.getLastBuild().getTimeInMillis(), status.getLastActivity());
        assertEquals(project.getLastBuild().getDuration(), status.getDuration());
    }


    @Test
    public void testResolveStatusAborted() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new MockBuilder(Result.ABORTED));
        project.scheduleBuild2(0);
        jenkins.waitUntilNoActivity();
        Status status = PipelineFactory.resolveStatus(project, project.getLastBuild());
        assertTrue(status.isCancelled());
        assertEquals("CANCELLED", status.toString());
        assertEquals(project.getLastBuild().getTimeInMillis(), status.getLastActivity());
        assertEquals(project.getLastBuild().getDuration(), status.getDuration());
    }


    @Test
    public void testResolveStatusQueued() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.scheduleBuild2(2);
        Status status = PipelineFactory.resolveStatus(project, null);
        assertTrue(status.isQueued());
        assertEquals("QUEUED", status.toString());
        jenkins.waitUntilNoActivity();
        status = PipelineFactory.resolveStatus(project, project.getLastBuild());
        assertTrue(status.isSuccess());
        assertEquals(project.getLastBuild().getDuration(), status.getDuration());
    }

    @Test
    public void testResolveStatusBuilding() throws Exception {
        final OneShotEvent buildStarted = new OneShotEvent();

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new TestBuilder() {
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                                   BuildListener listener) throws InterruptedException, IOException {
                buildStarted.signal();
                Thread.currentThread().wait(1000);
                return true;
            }
        });

        project.scheduleBuild2(0);
        buildStarted.block(); // wait for the build to really start
        Status status = PipelineFactory.resolveStatus(project, project.getFirstBuild());
        jenkins.waitUntilNoActivity();
        assertTrue(status.isRunning());
    }

}
