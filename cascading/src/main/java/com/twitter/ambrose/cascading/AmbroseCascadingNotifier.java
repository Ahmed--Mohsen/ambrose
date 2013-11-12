/*
Copyright ......

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.twitter.ambrose.cascading;


import cascading.flow.Flow;
import cascading.flow.FlowListener;
import cascading.flow.FlowStep;
import cascading.flow.FlowStepListener;
import cascading.flow.Flows;
import cascading.flow.hadoop.HadoopFlowStep;
import cascading.flow.planner.BaseFlowStep;
import cascading.stats.hadoop.HadoopStepStats;
import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import com.twitter.ambrose.model.DAGNode;
import com.twitter.ambrose.model.Event;
import com.twitter.ambrose.model.Job;
import com.twitter.ambrose.model.Workflow;
import com.twitter.ambrose.service.StatsWriteService;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.TaskReport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.commons.logging.Log;
import org.apache.hadoop.conf.Configuration;
import org.jgrapht.graph.SimpleDirectedGraph;

/**
 * CascadingNotifier that collects plan and job information from within a cascading
 * runtime, builds Ambrose model objects, and passes the objects to an Ambrose
 * StatsWriteService object. This listener can be used regardless of what mode
 * Ambrose is running in.
 *
 * @see EmbeddedAmbroseCascadingNotifier for a subclass that can be used to run
 * an embedded Ambrose web server from Main method.
 * @author Ahmed Mohsen
 */
public class AmbroseCascadingNotifier implements FlowListener, FlowStepListener {

    private static final Joiner COMMA_JOINER = Joiner.on(',');
    protected Log log = LogFactory.getLog(getClass());
    private StatsWriteService statsWriteService;
    private String workflowVersion;
    private List<Job> jobs = new ArrayList<Job>();
    private Map<String, DAGNode<CascadingJob>> dagNodeNameMap = Maps.newTreeMap();
    private Map<String, DAGNode<CascadingJob>> dagNodeJobIdMap = Maps.newTreeMap();
    private HashSet<String> completedJobIds = new HashSet<String>();
    private JobClient jobClient;
    private SimpleDirectedGraph jobGraph;
    private int totalNumberOfJobs;
    private int runnigJobs;
    private String currentFlowId;   //id of the flow being excuted

    /**
     * Intialize this class with an instance of StatsWriteService to push stats
     * to.
     *
     * @param statsWriteService
     */
    public AmbroseCascadingNotifier(StatsWriteService statsWriteService) {
        this.statsWriteService = statsWriteService;
    }

    protected StatsWriteService getStatsWriteService() {
        return statsWriteService;
    }

    /**
     * The onStarting event is fired when a Flow instance receives the start()
     * message. -a Flow is cut down into executing units called stepFlow
     * -stepFlow contains a stepFlowJob which represents the mapreduce job to be
     * submitted to Hadoop -the DAG graph is constructed from the step graph
     * found in flow object
     *
     * @param flow
     */
    @Override
    public void onStarting(Flow flow) {
        //init flow
        List<BaseFlowStep> steps = flow.getFlowSteps();
        totalNumberOfJobs = steps.size();
        runnigJobs = 0;
        currentFlowId = flow.getID();

        //Flows is a utility helper class to call the protected method getFlowStepGraph.
        Flows flows = null;
        // AmbroseCascadingGraphConvertor to convert the graph generated by cascading to
        // DAGNodes Graph to be sent to ambrose
        AmbroseCascadingGraphConverter convertor = new AmbroseCascadingGraphConverter((SimpleDirectedGraph) flows.getStepGraphFrom(flow), dagNodeNameMap);
        convertor.convert();
        try {
            statsWriteService.sendDagNodeNameMap(null, this.dagNodeNameMap);
        } catch (IOException e) {
            log.error("Couldn't send dag to StatsWriteService", e);
        }
        Workflow workflow = new Workflow(currentFlowId, currentFlowId, jobs);

    }

    /**
     * The onStopping event is fired when a Flow instance receives the stop()
     * message.
     *
     * @param flow
     */
    @Override
    public void onStopping(Flow flow) {}

    /**
     * The onCompleted event is fired when a Flow instance has completed all
     * work whether if was success or failed. If there was a thrown exception,
     * onThrowable will be fired before this event.
     *
     * @param flow
     */
    @Override
    public void onCompleted(Flow flow) {}

    /**
     * The onThrowable event is fired if any child
     * {@link cascading.flow.FlowStep} throws a Throwable type. This throwable
     * is passed as an argument to the event. This event method should return
     * true if the given throwable was handled and should not be rethrown from
     * the {@link Flow#complete()} method.
     *
     * @param flow
     * @param throwable
     * @return true if this listener has handled the given throwable
     */
    @Override
    public boolean onThrowable(Flow flow, Throwable throwable) {
        return false;
    }

    /**
     * onStepStarting event is fired whenever a job is submitted to Hadoop and begun
     * its excution
     *
     * @param flowStep the step in the flow that represents the MapReduce job
     */
    @Override
    public void onStepStarting(FlowStep flowStep) {
      //getting Hadoop job client
      HadoopStepStats stats = (HadoopStepStats)((HadoopFlowStep)flowStep).getFlowStepStats();
      String assignedJobId = stats.getJobID();
      String jobName = flowStep.getName();
      JobClient jc = stats.getJobClient();

      runnigJobs++; //update overall progress

      DAGNode<CascadingJob> node = this.dagNodeNameMap.get(jobName);
      if (node == null) {
        log.warn("jobStartedNotification - unrecognized operator name found ("
                + jobName + ") for jobId " + assignedJobId);
      } else {
        node.getJob().setId(assignedJobId);
        node.getJob().setJobStats(stats);
        addMapReduceJobState(node.getJob(), stats);

        dagNodeJobIdMap.put(node.getJob().getId(), node);
        pushEvent(currentFlowId, new Event.JobStartedEvent(node));

        //start progress pulling
        Thread progressReporter =  new StepProgressReporter(this,flowStep);
        progressReporter.start();
      }
    }
   
    /**
     * onStepCompleted event is fired when a stepFlowJob completed its work
     *
     * @param flowStep the step in the flow that represents the MapReduce job
     */
    @Override
    public void onStepCompleted(FlowStep flowStep) {
         HadoopStepStats stats = (HadoopStepStats)flowStep.getFlowStepStats();
         String jobId = stats.getJobID();

        //get job node
        DAGNode<CascadingJob> node = dagNodeJobIdMap.get(jobId);
        if (node == null) {
          log.warn("Unrecognized jobId reported for succeeded job: " + stats.getJobID());
          return;
        }
        addCompletedJobStats(node.getJob(), stats);
        pushEvent(currentFlowId, new Event.JobFinishedEvent(node));
    }

    /**
     * onStepThrowable event is fired if job failed during execution A job_failed
     * event is pushed with node represents the failed job
     *
     * @param flowStep the step in the flow that represents the MapReduce job
     * @param throwable  the exception that caused the job to fail
     */
    @Override
    public boolean onStepThrowable(FlowStep flowStep , Throwable throwable) {
      HadoopStepStats stats = (HadoopStepStats)flowStep.getFlowStepStats();
      String jobName = flowStep.getName();
      
      //get job node
      DAGNode<CascadingJob> node = dagNodeNameMap.get(jobName);
      if (node == null) {
        log.warn("Unrecognized jobId reported for succeeded job: " + stats.getJobID());
        return false;
      }
      addCompletedJobStats(node.getJob(), stats);
      pushEvent(currentFlowId, new Event.JobFailedEvent(node));
      return false;
    }

    /**
     * onStepProgressing event is fired whenever a job made a progress
     *
     * @param flowStep the step in the flow that represents the MapReduce job
     */
    @Override
    public void onStepProgressing(FlowStep flowStep) {
      //getting Hadoop running job and job client
      HadoopStepStats stats = (HadoopStepStats)flowStep.getFlowStepStats();
      JobClient jc = stats.getJobClient();

      // first we report the scripts progress
      int progress = (int) (((runnigJobs * 1.0) / totalNumberOfJobs) * 100);
      Map<Event.WorkflowProgressField, String> eventData = Maps.newHashMap();
      eventData.put(Event.WorkflowProgressField.workflowProgress, Integer.toString(progress));
      pushEvent(currentFlowId, new Event.WorkflowProgressEvent(eventData));

      //get job node
      String jobId = stats.getJobID();
      DAGNode<CascadingJob> node = dagNodeJobIdMap.get(jobId);
      if (node == null) {
        log.warn("Unrecognized jobId reported for succeeded job: " + stats.getJobID());
        return;
      }

      //only push job progress events for a completed job once
      if (node.getJob().getMapReduceJobState() != null && !completedJobIds.contains(node.getJob().getId())) {
        pushEvent(currentFlowId, new Event.JobProgressEvent(node));
        addMapReduceJobState(node.getJob(), stats);

        if (node.getJob().getMapReduceJobState().isComplete()) {
          completedJobIds.add(node.getJob().getId());
        }
      }
    }

    @Override
    public void onStepStopping(FlowStep flowStep) {
      //will be catched with failed event
    }


  /*
   * Collects statistics from JobStats and builds a nested Map of values.
   */
  private void addCompletedJobStats(CascadingJob job, HadoopStepStats stats) {
    if (workflowVersion == null)  {
      workflowVersion = currentFlowId;
    }

    job.setJobStats(stats);
    jobs.add(job);
  }

  private void pushEvent(String flowId, Event event) {
    try {
      statsWriteService.pushEvent(flowId, event);
    } catch (IOException e) {
      log.error("Couldn't send event to StatsWriteService", e);
    }
  }

  @SuppressWarnings("deprecation")
  private void addMapReduceJobState(CascadingJob cascadingJob,HadoopStepStats stats) {
    try {
      RunningJob runningJob = stats.getRunningJob();
      if (runningJob == null) {
        log.warn("Couldn't find job status for jobId=" + cascadingJob.getId());
        return;
      }
      String jobID = stats.getJobID();
      TaskReport[] mapTaskReport = stats.getJobClient().getMapTaskReports(jobID);
      TaskReport[] reduceTaskReport = stats.getJobClient().getReduceTaskReports(jobID);
      cascadingJob.setMapReduceJobState(new CascadingMapReduceJobState(stats, mapTaskReport, reduceTaskReport));
      Properties jobConfProperties = new Properties();
      Configuration conf = stats.getJobClient().getConf();
      for (Map.Entry<String, String> entry : conf) {
        jobConfProperties.setProperty(entry.getKey(), entry.getValue());
      }
      cascadingJob.setConfiguration(jobConfProperties);
    } catch (IOException ex) {
      log.error("Error getting job info.", ex);
    }
  }

  /**
   *  This thread is used to pull step progress
   */
  private class StepProgressReporter extends Thread{
    private AmbroseCascadingNotifier notifier;
    private FlowStep step;

    StepProgressReporter(AmbroseCascadingNotifier notifier, FlowStep step){
      this.notifier = notifier;
      this.step = step;
    }

    @Override
    public void run() {
      boolean stepFinished = false;
      while(!stepFinished){
        HadoopStepStats stats = (HadoopStepStats)step.getFlowStepStats();
        notifier.onStepProgressing(step);
        stepFinished = stats.isFinished() || stats.isStopped() ||
                stats.isFailed() || stats.isSuccessful();
        try {
          Thread.sleep(100);
        } catch (InterruptedException ex) {
          Logger.getLogger(AmbroseCascadingNotifier.class.getName()).log(Level.SEVERE, null, ex);
        }
      }
      

    }

  }
}
