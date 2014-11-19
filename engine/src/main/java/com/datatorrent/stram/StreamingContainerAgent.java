/**
 * Copyright (c) 2012-2013 DataTorrent, Inc.
 * All rights reserved.
 */
package com.datatorrent.stram;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.common.collect.Sets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.conf.YarnConfiguration;

import com.datatorrent.api.Context.PortContext;
import com.datatorrent.api.DAG.Locality;
import com.datatorrent.api.InputOperator;
import com.datatorrent.api.Operator;
import com.datatorrent.api.Operator.ProcessingMode;
import com.datatorrent.api.StorageAgent;
import com.datatorrent.api.StreamCodec;
import com.datatorrent.api.annotation.Stateless;

import com.datatorrent.stram.api.Checkpoint;
import com.datatorrent.stram.api.OperatorDeployInfo;
import com.datatorrent.stram.api.OperatorDeployInfo.InputDeployInfo;
import com.datatorrent.stram.api.OperatorDeployInfo.OperatorType;
import com.datatorrent.stram.api.OperatorDeployInfo.OutputDeployInfo;
import com.datatorrent.stram.api.StreamingContainerUmbilicalProtocol.StramToNodeRequest;
import com.datatorrent.stram.api.StreamingContainerUmbilicalProtocol.StreamingContainerContext;
import com.datatorrent.stram.engine.OperatorContext;
import com.datatorrent.stram.plan.logical.LogicalPlan;
import com.datatorrent.stram.plan.logical.LogicalPlan.InputPortMeta;
import com.datatorrent.stram.plan.logical.LogicalPlan.StreamMeta;
import com.datatorrent.stram.plan.physical.PTContainer;
import com.datatorrent.stram.plan.physical.PTOperator;
import com.datatorrent.stram.plan.physical.PTOperator.State;
import com.datatorrent.stram.plan.physical.PhysicalPlan;
import com.datatorrent.stram.util.ConfigUtils;
import com.datatorrent.stram.webapp.ContainerInfo;

/**
 *
 * Representation of child container (execution layer) in the master<p>
 * Created when resource for container was allocated.
 * Destroyed after resource is deallocated (container released, killed etc.)
 * <br>
 *
 * @since 0.3.2
 */
public class StreamingContainerAgent {
  private static final Logger LOG = LoggerFactory.getLogger(StreamingContainerAgent.class);

  public static class ContainerStartRequest {
    final PTContainer container;

    ContainerStartRequest(PTContainer container) {
      this.container = container;
    }
  }


  public StreamingContainerAgent(PTContainer container, StreamingContainerContext initCtx, StreamingContainerManager dnmgr) {
    this.container = container;
    this.initCtx = initCtx;
    this.memoryMBFree = this.container.getAllocatedMemoryMB();
    this.dnmgr = dnmgr;
  }

  boolean shutdownRequested = false;

  Set<PTOperator> deployOpers = Sets.newHashSet();
  Set<Integer> undeployOpers = Sets.newHashSet();
  int deployCnt = 0;

  long lastHeartbeatMillis = 0;
  long createdMillis = System.currentTimeMillis();
  final PTContainer container;
  final StreamingContainerContext initCtx;
  String jvmName;
  int memoryMBFree;
  final StreamingContainerManager dnmgr;

  private final ConcurrentLinkedQueue<StramToNodeRequest> operatorRequests = new ConcurrentLinkedQueue<StramToNodeRequest>();

  public StreamingContainerContext getInitContext() {
    return initCtx;
  }

  public PTContainer getContainer()
  {
    return container;
  }

  public boolean hasPendingWork() {
    for (PTOperator oper : container.getOperators()) {
      if (oper.getState() == PTOperator.State.PENDING_DEPLOY) {
        return true;
      }
    }
    return false;
  }

  public void addOperatorRequest(StramToNodeRequest r) {
    LOG.info("Adding operator request {} {}", container.getExternalId(), r);
    this.operatorRequests.add(r);
  }

  @SuppressWarnings("ReturnOfCollectionOrArrayField")
  protected ConcurrentLinkedQueue<StramToNodeRequest> getOperatorRequests() {
    return this.operatorRequests;
  }

  /**
   * Create deploy info for StramChild.
   * @param operators
   * @return StreamingContainerContext
   */
  public List<OperatorDeployInfo> getDeployInfoList(Collection<PTOperator> operators) {

    if (container.bufferServerAddress == null) {
      throw new AssertionError("No buffer server address assigned");
    }

    Map<OperatorDeployInfo, PTOperator> nodes = new LinkedHashMap<OperatorDeployInfo, PTOperator>();
    HashSet<PTOperator.PTOutput> publishers = new HashSet<PTOperator.PTOutput>();

    PhysicalPlan physicalPlan = dnmgr.getPhysicalPlan();

    for (PTOperator oper : operators) {
      if (oper.getState() != State.PENDING_DEPLOY) {
        LOG.debug("Skipping deploy for operator {} state {}", oper, oper.getState());
        continue;
      }
      OperatorDeployInfo ndi = createOperatorDeployInfo(oper);

      nodes.put(ndi, oper);
      ndi.inputs = new ArrayList<InputDeployInfo>(oper.getInputs().size());
      ndi.outputs = new ArrayList<OutputDeployInfo>(oper.getOutputs().size());

      for (PTOperator.PTOutput out : oper.getOutputs()) {
        final StreamMeta streamMeta = out.logicalStream;
        // buffer server or inline publisher
        OutputDeployInfo portInfo = new OutputDeployInfo();
        portInfo.declaredStreamId = streamMeta.getName();
        portInfo.portName = out.portName;

        //portInfo.contextAttributes = streamMeta.getSource().getAttributes();
        portInfo.contextAttributes = streamMeta.getSource().getAttributes().clone();

        boolean outputUnified = false;
        for (PTOperator.PTInput input : out.sinks) {
          if (input.target.isUnifier()) {
            outputUnified = true;
            break;
          }
        }
        portInfo.contextAttributes.put(PortContext.IS_OUTPUT_UNIFIED, outputUnified);

        if (ndi.type == OperatorDeployInfo.OperatorType.UNIFIER) {
          // input attributes of the downstream operator
          for (InputPortMeta sink : streamMeta.getSinks()) {
            portInfo.contextAttributes = sink.getAttributes();
            break;
          }
        }

        if (!out.isDownStreamInline()) {
          portInfo.bufferServerHost = oper.getContainer().bufferServerAddress.getHostName();
          portInfo.bufferServerPort = oper.getContainer().bufferServerAddress.getPort();
          // Build the stream codec configuration of all sinks connected to this port
          for (PTOperator.PTInput input : out.sinks) {
            // Create mappings for all non-inline operators
            if (input.target.getContainer() != out.source.getContainer()) {
              InputPortMeta inputPortMeta = getIdentifyingInputPortMeta(input);
              StreamCodec<?> streamCodecInfo = getStreamCodec(inputPortMeta);
              Integer id = physicalPlan.getStreamCodecIdentifier(streamCodecInfo);
              if (!portInfo.streamCodecs.containsKey(id)) {
                portInfo.streamCodecs.put(id, streamCodecInfo);
              }
            }
          }
        }

        ndi.outputs.add(portInfo);
        publishers.add(out);
      }
    }

    // after we know all publishers within container, determine subscribers

    for (Map.Entry<OperatorDeployInfo, PTOperator> operEntry : nodes.entrySet()) {
      OperatorDeployInfo ndi = operEntry.getKey();
      PTOperator oper = operEntry.getValue();
      for (PTOperator.PTInput in : oper.getInputs()) {
        final StreamMeta streamMeta = in.logicalStream;
        if (streamMeta.getSource() == null) {
          throw new AssertionError("source is null: " + in);
        }
        PTOperator.PTOutput sourceOutput = in.source;

        InputDeployInfo inputInfo = new InputDeployInfo();
        inputInfo.declaredStreamId = streamMeta.getName();
        inputInfo.portName = in.portName;
        InputPortMeta inputPortMeta = getInputPortMeta(oper.getOperatorMeta(), streamMeta);

        if (inputPortMeta != null) {
          inputInfo.contextAttributes = inputPortMeta.getAttributes();
        }

        if (inputInfo.contextAttributes == null && ndi.type == OperatorDeployInfo.OperatorType.UNIFIER) {
          inputInfo.contextAttributes = in.source.logicalStream.getSource().getAttributes();
        }

        inputInfo.sourceNodeId = sourceOutput.source.getId();
        inputInfo.sourcePortName = sourceOutput.portName;
        if (in.partitions != null && in.partitions.mask != 0) {
          inputInfo.partitionMask = in.partitions.mask;
          inputInfo.partitionKeys = in.partitions.partitions;
        }

        if (sourceOutput.source.getContainer() == oper.getContainer()) {
          // both operators in same container
          if (!publishers.contains(sourceOutput)) {
            throw new AssertionError("Source not deployed for container local stream " + sourceOutput + " " + in);
          }
          if (streamMeta.getLocality() == Locality.THREAD_LOCAL) {
            inputInfo.locality = Locality.THREAD_LOCAL;
            ndi.type = OperatorType.OIO;
          } else {
            inputInfo.locality = Locality.CONTAINER_LOCAL;
          }

        } else {
          // buffer server input
          InetSocketAddress addr = sourceOutput.source.getContainer().bufferServerAddress;
          if (addr == null) {
            throw new AssertionError("upstream address not assigned: " + sourceOutput);
          }
          inputInfo.bufferServerHost = addr.getHostName();
          inputInfo.bufferServerPort = addr.getPort();
        }

        // On the input side there is a unlikely scenario of partitions even for inline stream that is being
        // handled. Always specifying a stream codec configuration in case that scenario happens.
        InputPortMeta idInputPortMeta = getIdentifyingInputPortMeta(in);
        StreamCodec<?> streamCodecInfo = getStreamCodec(idInputPortMeta);
        Integer id = physicalPlan.getStreamCodecIdentifier(streamCodecInfo);
        inputInfo.streamCodecs.put(id, streamCodecInfo);
        ndi.inputs.add(inputInfo);
      }
    }

    return new ArrayList<OperatorDeployInfo>(nodes.keySet());
  }

  public static InputPortMeta getInputPortMeta(LogicalPlan.OperatorMeta operatorMeta, StreamMeta streamMeta)
  {
    InputPortMeta inputPortMeta = null;
    Map<InputPortMeta, StreamMeta> inputStreams = operatorMeta.getInputStreams();
    for (Map.Entry<InputPortMeta, StreamMeta> entry : inputStreams.entrySet()) {
      if (entry.getValue() == streamMeta) {
        inputPortMeta = entry.getKey();
        break;
      }
    }
    return inputPortMeta;
  }

  public static InputPortMeta getIdentifyingInputPortMeta(PTOperator.PTInput input)
  {
    InputPortMeta inputPortMeta;
    PTOperator inputTarget = input.target;
    StreamMeta streamMeta = input.logicalStream;
    if (!inputTarget.isUnifier()) {
      inputPortMeta = getInputPortMeta(inputTarget.getOperatorMeta(), streamMeta);
    } else {
      PTOperator destTarget = getIdentifyingOperator(inputTarget);
      inputPortMeta = getInputPortMeta(destTarget.getOperatorMeta(), streamMeta);
    }
    return inputPortMeta;
  }

  public static PTOperator getIdentifyingOperator(PTOperator operator)
  {
    while ((operator != null) && operator.isUnifier()) {
      PTOperator idOperator = null;
      List<PTOperator.PTOutput> outputs = operator.getOutputs();
      // Since it is a unifier, getting the downstream operator it is connected to which is on the first port
      if (outputs.size() > 0) {
        List<PTOperator.PTInput> sinks = outputs.get(0).sinks;
        if (sinks.size() > 0) {
          PTOperator.PTInput sink = sinks.get(0);
          idOperator = sink.target;
        }
      }
      operator = idOperator;
    }
    return operator;
  }

  public static StreamCodec<?> getStreamCodec(InputPortMeta inputPortMeta)
  {
    if (inputPortMeta != null) {
      StreamCodec<?> codec = inputPortMeta.getValue(PortContext.STREAM_CODEC);
      if (codec == null) {
        // it cannot be this object that gets returned. Depending upon this value is dangerous -- Chetan (Pramod look into this.)
        codec = inputPortMeta.getPortObject().getStreamCodec();
        if (codec != null) {
          // don't create codec multiple times - it will assign a new identifier
          inputPortMeta.getAttributes().put(PortContext.STREAM_CODEC, codec);
        }
      }
      return codec;
    }
    return null;
  }

  /**
   * Create deploy info for operator.
   * <p>
   *
   * @return {@link com.datatorrent.stram.api.OperatorDeployInfo}
   *
   */
  private OperatorDeployInfo createOperatorDeployInfo(PTOperator oper)
  {
    OperatorDeployInfo ndi = new OperatorDeployInfo();
    Operator operator = oper.getOperatorMeta().getOperator();
    if (operator instanceof InputOperator) {
      ndi.type = OperatorType.INPUT;

      if (!oper.getInputs().isEmpty()) {
        //If there are no input ports then it has to be an input operator. But if there are input ports then
        //we check if any input port is connected which would make it a Generic operator.
        for (PTOperator.PTInput ptInput : oper.getInputs()) {
          if (ptInput.logicalStream != null && ptInput.logicalStream.getSource() != null) {
            ndi.type = OperatorType.GENERIC;
            break;
          }
        }
      }
    }
    else {
      ndi.type = OperatorType.GENERIC;
    }
    if (oper.isUnifier()) {
      ndi.type = OperatorDeployInfo.OperatorType.UNIFIER;
    }

    Checkpoint checkpoint = oper.getRecoveryCheckpoint();
    ProcessingMode pm = oper.getOperatorMeta().getValue(OperatorContext.PROCESSING_MODE);

    if (pm == ProcessingMode.AT_MOST_ONCE || pm == ProcessingMode.EXACTLY_ONCE) {
      // TODO: following should be handled in the container at deploy time
      // for exactly once container should also purge previous checkpoint
      // whenever new checkpoint is written.
      StorageAgent agent = oper.getOperatorMeta().getAttributes().get(OperatorContext.STORAGE_AGENT);
      if (agent == null) {
        agent = initCtx.getValue(OperatorContext.STORAGE_AGENT);
      }
      // pick the checkpoint most recently written to HDFS
      // this should be handled differently. What happens to the checkpoint reported?
      try {
        long[] windowIds = agent.getWindowIds(oper.getId());
        long checkpointId = Stateless.WINDOW_ID;
        for (long windowId : windowIds) {
          if (windowId > checkpointId) {
            checkpointId = windowId;
          }
        }
        if (checkpoint == null || checkpoint.windowId != checkpointId) {
          checkpoint = new Checkpoint(checkpointId, 0, 0);
        }
      }
      catch (Exception e) {
          throw new RuntimeException("Failed to determine checkpoint window id " + oper, e);
      }
    }

    LOG.debug("{} recovery checkpoint {}", oper, checkpoint);
    ndi.checkpoint = checkpoint;
    ndi.name = oper.getOperatorMeta().getName();
    ndi.id = oper.getId();
    // clone the map as StramChild assumes ownership and may add non-serializable attributes
    ndi.contextAttributes = oper.getOperatorMeta().getAttributes().clone();
    if (oper.isOperatorStateLess()) {
      ndi.contextAttributes.put(OperatorContext.STATELESS, true);
    }
    return ndi;
  }

  public ContainerInfo getContainerInfo() {
    ContainerInfo ci = new ContainerInfo();
    ci.id = container.getExternalId();
    ci.host = container.host;
    ci.state = container.getState().name();
    ci.jvmName = this.jvmName;
    ci.numOperators = container.getOperators().size();
    ci.memoryMBAllocated = container.getAllocatedMemoryMB();
    ci.lastHeartbeat = lastHeartbeatMillis;
    ci.memoryMBFree = this.memoryMBFree;
    ci.startedTime = container.getStartedTime();
    ci.finishedTime = container.getFinishedTime();
    if (this.container.nodeHttpAddress != null) {
      YarnConfiguration conf = new YarnConfiguration();
      ci.containerLogsUrl = ConfigUtils.getSchemePrefix(conf) + this.container.nodeHttpAddress + "/node/containerlogs/" + ci.id + "/" + System.getenv(ApplicationConstants.Environment.USER.toString());
      ci.rawContainerLogsUrl = ConfigUtils.getRawContainerLogsUrl(conf, container.nodeHttpAddress, container.getPlan().getLogicalPlan().getAttributes().get(LogicalPlan.APPLICATION_ID), ci.id);
    }
    return ci;
  }

}