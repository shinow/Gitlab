package org.springblade.flow.business.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.constants.BpmnXMLConstants;
import org.flowable.bpmn.model.*;
import org.flowable.bpmn.model.Process;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.editor.language.json.converter.util.CollectionUtils;
import org.flowable.engine.*;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.impl.persistence.entity.ActivityInstanceEntity;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ActivityInstance;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.springblade.common.constant.flow.FlowDesignUserType;
import org.springblade.common.constant.flow.FlowEngineConstant;
import org.springblade.core.log.exception.ServiceException;
import org.springblade.core.secure.BladeUser;
import org.springblade.core.secure.utils.AuthUtil;
import org.springblade.core.tool.api.R;
import org.springblade.core.tool.api.ServiceCode;
import org.springblade.core.tool.support.Kv;
import org.springblade.core.tool.utils.CollectionUtil;
import org.springblade.core.tool.utils.Func;
import org.springblade.core.tool.utils.StringPool;
import org.springblade.core.tool.utils.StringUtil;
import org.springblade.flow.business.factory.FactoryGetUser;
import org.springblade.flow.business.common.CommentTypeEnum;
import org.springblade.flow.business.common.cmd.BackUserTaskCmd;
import org.springblade.flow.business.mapper.IHisFlowableActinstDaoMapper;
import org.springblade.flow.business.mapper.IRunFlowableActinstDaoMapper;
import org.springblade.flow.business.service.FlowBusinessService;
import org.springblade.flow.business.service.IProcessService;
import org.springblade.flow.core.entity.BladeFlow;
import org.springblade.flow.core.entity.FlowNodeVo;
import org.springblade.flow.core.entity.ProcessEntity;
import org.springblade.flow.core.utils.TaskUtil;
import org.springblade.flow.core.vo.FlowNodeRequest;
import org.springblade.flow.core.vo.FlowNodeResponse;
import org.springblade.flow.core.vo.FlowUserRequest;
import org.springblade.flow.engine.utils.FlowCache;
import org.springblade.flow.engine.utils.FlowableUtils;
import org.springblade.flow.business.vo.TaskRequest;
import org.springblade.system.entity.Dept;
import org.springblade.system.feign.ISysClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 流程业务实现类
 *
 * @author 田爱华、史智伟
 * @date 2020-8-26
 */
@Slf4j
@Service
@AllArgsConstructor
public class FlowBusinessServiceImpl extends BaseProcessService implements FlowBusinessService {

	private TaskService taskService;
	private HistoryService historyService;
	private RepositoryService repositoryService;
	private RuntimeService runtimeService;
	protected ManagementService managementService;
	private IRunFlowableActinstDaoMapper runFlowableActinstDao;
	private IHisFlowableActinstDaoMapper hisFlowableActinstDao;
	private ISysClient sysClient;
	private IProcessService processService;

	/**
	 * 提交操作
	 *
	 * @param flowNodeResponseList
	 * @return 提交结果(boolean)
	 */
	@Override
	public boolean completeTask(List<FlowNodeResponse> flowNodeResponseList) {
		if (Func.isEmpty(flowNodeResponseList)) {
			log.error("【错误码{}】：流程提交时前台返回节点和用户信息为空",ServiceCode.FLOW_FAIL.getCode());
			throw new ServiceException(ServiceCode.FLOW_FAIL);
		}
		FlowNodeResponse flow = flowNodeResponseList.get(0);
		String taskId = flow.getTaskId();
		/* 获取taskEntity对象 */
		TaskEntity taskEntity = (TaskEntity) taskService.createTaskQuery().taskId(taskId).singleResult();
		String processInstanceId = taskEntity.getProcessInstanceId();
		String comment = Func.toStr(flow.getComment(), "");
		Map<String, Object> variables = Kv.create();
		/* 增加评论 */
		if (StringUtil.isNoneBlank(processInstanceId, comment)) {
			String userId = this.getLoginId();
			this.addComment(taskId, processInstanceId, userId, CommentTypeEnum.SP, comment);
		}
		/* 当前节点id */
		String currActId = taskEntity.getTaskDefinitionKey();
		/* 流程定义id */
		String processDefinitionId = taskEntity.getProcessDefinitionId();
		/* 获取流程实例对象 */
		Process process = repositoryService.getBpmnModel(processDefinitionId).getMainProcess();
		/* 获取当前节点 */
		FlowNode currentFlowElement = (FlowNode) process.getFlowElement(currActId, true);
		/* 获取下一节点，多出线的情况在首次提交时已经判断过了 */
		FlowNode targetFlowElement = (FlowNode) currentFlowElement.getOutgoingFlows().get(0).getTargetFlowElement();
		/* 如果是结束节点，直接提交流程 */
		if (targetFlowElement instanceof EndEvent) {
			taskService.complete(taskId);
			return true;
		}
		/* 提交下一节点是UserTask */
		if (targetFlowElement instanceof UserTask) {
			/* 将下一审批人赋给全局变量 */
			variables.put(targetFlowElement.getId(), TaskUtil.getTaskUser(flow.getUserResponseList().get(0).getId()));
			/* 完成任务 */
			taskService.complete(taskId, variables);
			return true;
		}
		/* 提交下一节点是排他或并行网关 */
		if (targetFlowElement instanceof ExclusiveGateway || targetFlowElement instanceof ParallelGateway) {
			/* 根据前台返回的节点List和用户List给全局变量赋值 */
			/* 此处只要给参数循环赋值即可，无需关心执行单条或多条分支，flowable会自行判断，只要保证相应节点有任务处理人即可 */
			flowNodeResponseList.forEach(flowNodeResponse -> variables.put(flowNodeResponse.getId(), TaskUtil.getTaskUser(flowNodeResponse.getUserResponseList().get(0).getId())));
			/* 完成任务 */
			taskService.complete(taskId, variables);
			return true;
		}
		/* 提交下一节点是包容网关 */
		if (targetFlowElement instanceof InclusiveGateway) {
			/* 遍历每一条连线获取目标节点id */
			targetFlowElement.getOutgoingFlows().forEach(sequenceFlow -> {
				String nodeId = sequenceFlow.getTargetFlowElement().getId();
				/* 用目标节点id作为条件过滤前台返回的List */
				List<FlowNodeResponse> result = flowNodeResponseList.stream()
					.filter(flowNodeResponse -> flowNodeResponse.getId().equals(nodeId))
					.collect(Collectors.toList());
				/* 如果结果不为空，表示需要提交给该节点，将该条连线的条件设置为true */
				if (Func.isNotEmpty(result)) {
					/* 根据前台返回节点信息确定需要给哪个连线设置true */
					sequenceFlow.setConditionExpression(FlowEngineConstant.FLOW_TRUE);
				}
			});
			/* 设置任务处理人 */
			flowNodeResponseList.forEach(flowNodeResponse -> variables.put(flowNodeResponse.getId(), TaskUtil.getTaskUser(flowNodeResponse.getUserId())));
			/* 完成任务 */
			taskService.complete(taskId, variables);
			return true;
		}
		return false;
	}

	/**
	 * 点击通过按钮时，查询下一节点和人员信息
	 *
	 * @param taskId 待办id
	 * @return 可以提交的节点信息
	 */
	@Override
	public List<FlowNodeRequest> completeTempResult(String taskId) {
		/* 获取taskEntity对象 */
		TaskEntity taskEntity = (TaskEntity) taskService.createTaskQuery().taskId(taskId).singleResult();
		/* 当前节点id */
		String currActId = taskEntity.getTaskDefinitionKey();
		/* 流程定义id */
		String processDefinitionId = taskEntity.getProcessDefinitionId();
		/* 获取流程实例对象 */
		Process process = repositoryService.getBpmnModel(processDefinitionId).getMainProcess();
		/* 获取当前节点 */
		FlowNode currentFlowElement = (FlowNode) process.getFlowElement(currActId, true);
		/* 获取当前节点的连线数 */
		List<SequenceFlow> outgoingFlows = currentFlowElement.getOutgoingFlows();
		/* 只有一条出线 */
		if (outgoingFlows.size() == 1) {
			/* 获取并判断下一节点是结束节点EndEvent、用户节点UserTask、网关节点GateWay */
			FlowNode targetNode = (FlowNode) outgoingFlows.get(0).getTargetFlowElement();
			/* EndEvent */
			if (targetNode instanceof EndEvent) {
				return createFlowNodeResponseEnd(targetNode);
			}
			/* 提交下一节点是UserTask */
			if (targetNode instanceof UserTask) {
				return createFlowNodeResponseUserTask(targetNode, taskId);
			}
			/* 提交下一节点是GateWay */
			if (targetNode instanceof Gateway) {
				/* 获取流程全局变量 */
				return createFlowNodeResponseGateWay(targetNode, taskId);
			}
			/* 都没有满足以上的话，可能是其他类型的节点，流程目前暂不支持 */
			log.error("【错误码{}】：无法识别下一节点，下一节点是EndEvent、UserTask、GateWay之外的其他节点", ServiceCode.FLOW_UNKNOWN_NODE.getCode());
			throw new ServiceException(ServiceCode.FLOW_UNKNOWN_NODE);
		} else {
			/* 目前流程不允许多条出线的情况 */
			log.error("【错误码{}】：流程要求节点的出线数为1条，当前节点的出线数为{}条", ServiceCode.FLOW_UNKNOWN_OUTLINE.getCode(), outgoingFlows.size());
			throw new ServiceException(ServiceCode.FLOW_UNKNOWN_OUTLINE);
		}
	}

	/**
	 * 封装【结束节点】的FlowNodeResponseList
	 *
	 * @param targetNode 节点对象
	 * @return 可以提交的节点信息
	 */
	private List<FlowNodeRequest> createFlowNodeResponseEnd(FlowNode targetNode) {
		List<FlowNodeRequest> flowNodeRequestList = new ArrayList<>();
		FlowNodeRequest flowNodeRequest = FlowNodeRequest.builder()
			.id(targetNode.getId())
			.name("结束节点")
			.end(true)
			.build();
		flowNodeRequestList.add(flowNodeRequest);
		return flowNodeRequestList;
	}

	/**
	 * 封装【UserTask节点】的FlowNodeResponseList
	 *
	 * @param targetNode 节点对象
	 * @param taskId     执行实例id
	 * @return 可以提交的节点信息
	 */
	private List<FlowNodeRequest> createFlowNodeResponseUserTask(FlowNode targetNode, String taskId) {
		/* 获取节点自定义属性 */
		List<FlowUserRequest> candidateUserList = getCandidateUsers(targetNode, taskId);
		List<FlowNodeRequest> flowNodeRequestList = new ArrayList<>();
		FlowNodeRequest flowNodeRequest = FlowNodeRequest.builder()
			.taskId(taskId)
			.id(targetNode.getId())
			.name(targetNode.getName())
			.end(false)
			.userResponseList(candidateUserList)
			.build();
		flowNodeRequestList.add(flowNodeRequest);
		return flowNodeRequestList;
	}

	/**
	 * 封装【GateWay节点】的FlowNodeResponseList
	 *
	 * @param targetNode 节点对象
	 * @param taskId     执行实例id
	 * @return 可以提交的节点信息
	 */
	public List<FlowNodeRequest> createFlowNodeResponseGateWay(FlowNode targetNode, String taskId) {
		List<FlowNodeRequest> flowNodeRequestList = new ArrayList<>();
		/* 并行网关，所有分支必须执行，无法自由选择 */
		if (targetNode instanceof ParallelGateway) {
			/* 遍历并行网关的每一条出线获取每一个节点 */
			targetNode.getOutgoingFlows().forEach(sequenceFlow -> {
				FlowNode targetFlowNode = (FlowNode) sequenceFlow.getTargetFlowElement();
				/* 获取节点自定义属性并返回人员List */
				List<FlowUserRequest> candidateUserList = getCandidateUsers(targetFlowNode, taskId);
				FlowNodeRequest flowNodeRequest = FlowNodeRequest.builder()
					.taskId(taskId)
					.id(targetFlowNode.getId())
					.name(targetFlowNode.getName())
					.end(false)
					.userResponseList(candidateUserList)
					.build();
				flowNodeRequestList.add(flowNodeRequest);
			});
			return flowNodeRequestList;
		}
		/* 排他网关，所有分支中满足条件的第一个分支会执行，其余均不执行 */
		/* 注：此处的判断只是为了前台展示下一节点和处理人，流程执行时flowable会根据全局变量自行判断连线的走向 */
		if (targetNode instanceof ExclusiveGateway) {
			/*获取全局变量，全局变量是在启动流程时存入的变量，以Key Value的形式保存*/
			Map<String, Object> variables = taskService.getVariables(taskId);
			if (Func.isEmpty(variables)) {
				log.error("【错误码{}】：排他网关未获取到流程参数，流程无法选择分支", ServiceCode.FLOW_VARIABLES_NOT_FOUND.getCode());
				throw new ServiceException(ServiceCode.FLOW_VARIABLES_NOT_FOUND);
			}
			ScriptEngineManager manager = new ScriptEngineManager();
			/* 遍历所有连线，确定流程即将进入的连线 */
			for (SequenceFlow outLine : targetNode.getOutgoingFlows()) {
				/* 获取连线的条件 */
				String conditionExpression = outLine.getConditionExpression();
				/* 去掉$、{、}， */
				conditionExpression = conditionExpression.replace("$", "")
					.replace("{", "")
					.replace("}", "");
				/* 遍历全局变量，将全局变量中与条件表达式对应的key替换为全局变量的value */
				/* 得到类似：value（全局变量） == value（连线表达式） 的字符串表达式 */
				for (Map.Entry<String, Object> map : variables.entrySet()) {
					conditionExpression = conditionExpression.replace(map.getKey(), map.getValue().toString());
				}
				/* 调用js对字符串表达式进行计算，可以计算数字和字符串：1==1返回true，'a'=='a'返回true */
				try {
					ScriptEngine engine = manager.getEngineByName("js");
					boolean result = (boolean) engine.eval(conditionExpression);
					if (result) {
						FlowNode flowNodeExclusive = (FlowNode) outLine.getTargetFlowElement();
						/* 获取节点自定义属性 */
						List<FlowUserRequest> candidateUserList = getCandidateUsers(flowNodeExclusive, taskId);
						FlowNodeRequest flowNodeRequest = FlowNodeRequest.builder()
							.end(false)
							.taskId(taskId)
							.id(flowNodeExclusive.getId())
							.name(flowNodeExclusive.getName())
							.userResponseList(candidateUserList)
							.build();
						flowNodeRequestList.add(flowNodeRequest);
						/* 排他网关只能有一条线可以走，只要遇到满足条件的就结束循环 */
						break;
					}
				} catch (ScriptException e) {
					log.error("【错误码{}】：JS表达式判断出现异常，表达式为【{}】", ServiceCode.FLOW_SCRIPT_ENGINE_EXCEPTION.getCode(), conditionExpression);
					throw new ServiceException(ServiceCode.FLOW_SCRIPT_ENGINE_EXCEPTION);
				}
			}
			return flowNodeRequestList;
		}
		/* 包容网关，可以选择一条或多条出线，此处在前台展示所有可选的节点和用户信息 */
		if (targetNode instanceof InclusiveGateway) {
			targetNode.getOutgoingFlows().forEach(sequenceFlow -> {
				FlowNode targetNodeInclusive = (FlowNode) sequenceFlow.getTargetFlowElement();
				List<FlowUserRequest> candidateUserList = getCandidateUsers(targetNodeInclusive, taskId);
				FlowNodeRequest flowNodeRequest = FlowNodeRequest.builder()
					.end(false)
					.taskId(taskId)
					.enableChooseNode(true)
					.id(targetNodeInclusive.getId())
					.name(targetNodeInclusive.getName())
					.userResponseList(candidateUserList)
					.build();
				flowNodeRequestList.add(flowNodeRequest);
			});
			return flowNodeRequestList;
		}
		log.error("【错误码{}】：流程暂不支持其他网关类型(排他，包容，并行之外的类型)的处理", ServiceCode.FLOW_UNKNOWN_GATEWAY.getCode());
		throw new ServiceException(ServiceCode.FLOW_UNKNOWN_GATEWAY);
	}

	@Override
	public List<FlowUserRequest> getCandidateUsers(FlowNode targetNode, String... taskId) {
		/* 获取候选人名单获取方式 */
		String getUserType = targetNode.getAttributeValue(FlowEngineConstant.NAME_SPACE, FlowEngineConstant.GET_USER_TYPE);
		if (Func.isEmpty(getUserType) || Func.equals(getUserType, FlowDesignUserType.NONE)) {
			return null;
		}
		return FactoryGetUser.getInvokeStrategy(getUserType).getUser(targetNode, (taskId.length == 0 ? "" : taskId[0]));
	}

	@Override
	public IPage<BladeFlow> selectClaimPage(IPage<BladeFlow> page, BladeFlow bladeFlow) {
		String taskUser = TaskUtil.getTaskUser();
		String taskGroup = TaskUtil.getCandidateGroup();
		List<BladeFlow> flowList = new LinkedList<>();
		/* 个人等待签收的任务 */
		TaskQuery claimUserQuery = taskService.createTaskQuery().taskCandidateUser(taskUser)
			.includeProcessVariables().active().orderByTaskCreateTime().desc();
		/* 定制流程等待签收的任务 */
		TaskQuery claimRoleWithTenantIdQuery = taskService.createTaskQuery().taskTenantId(AuthUtil.getTenantId()).taskCandidateGroupIn(Func.toStrList(taskGroup))
			.includeProcessVariables().active().orderByTaskCreateTime().desc();
		/* 通用流程等待签收的任务 */
		TaskQuery claimRoleWithoutTenantIdQuery = taskService.createTaskQuery().taskWithoutTenantId().taskCandidateGroupIn(Func.toStrList(taskGroup))
			.includeProcessVariables().active().orderByTaskCreateTime().desc();
		/* 构建列表数据 */
		buildFlowTaskList(bladeFlow, flowList, claimUserQuery, FlowEngineConstant.STATUS_CLAIM);
		buildFlowTaskList(bladeFlow, flowList, claimRoleWithTenantIdQuery, FlowEngineConstant.STATUS_CLAIM);
		buildFlowTaskList(bladeFlow, flowList, claimRoleWithoutTenantIdQuery, FlowEngineConstant.STATUS_CLAIM);
		/* 计算总数 */
		long count = claimUserQuery.count() + claimRoleWithTenantIdQuery.count() + claimRoleWithoutTenantIdQuery.count();
		page.setSize(count);
		page.setTotal(count);
		page.setRecords(flowList);
		return page;
	}

	@Override
	public IPage<BladeFlow> selectTodoPage(IPage<BladeFlow> page, BladeFlow bladeFlow) {
		String taskUser = TaskUtil.getTaskUser();
		List<BladeFlow> flowList = new LinkedList<>();
		/* 已签收的任务 */
		TaskQuery todoQuery = taskService.createTaskQuery().taskAssignee(taskUser).active()
			.includeProcessVariables().orderByTaskCreateTime().desc();
		/* 构建列表数据 */
		buildFlowTaskList(bladeFlow, flowList, todoQuery, FlowEngineConstant.STATUS_TODO);
		/* 计算总数 */
		long count = todoQuery.count();
		page.setSize(count);
		page.setTotal(count);
		page.setRecords(flowList);
		return page;
	}

	@Override
	public IPage<BladeFlow> selectSendPage(IPage<BladeFlow> page, BladeFlow bladeFlow) {
		String taskUser = TaskUtil.getTaskUser();
		List<BladeFlow> flowList = new LinkedList<>();
		HistoricProcessInstanceQuery historyQuery = historyService.createHistoricProcessInstanceQuery().startedBy(taskUser).orderByProcessInstanceStartTime().desc();
		if (Func.isNotEmpty(bladeFlow.getCategory())) {
			historyQuery.processDefinitionCategory(bladeFlow.getCategory());
		}
		if (Func.isNotEmpty(bladeFlow.getBeginDate())) {
			historyQuery.startedAfter(bladeFlow.getBeginDate());
		}
		if (Func.isNotEmpty(bladeFlow.getEndDate())) {
			historyQuery.startedBefore(bladeFlow.getEndDate());
		}
		/* 查询列表 */
		List<HistoricProcessInstance> historyList = historyQuery.listPage(Func.toInt((page.getCurrent() - 1) * page.getSize()), Func.toInt(page.getSize()));
		historyList.forEach(historicProcessInstance -> {
			BladeFlow flow = new BladeFlow();
			/* historicProcessInstance */
			flow.setCreateTime(historicProcessInstance.getStartTime());
			flow.setEndTime(historicProcessInstance.getEndTime());
			flow.setVariables(historicProcessInstance.getProcessVariables());
			String[] businessKey = Func.toStrArray(StringPool.COLON, historicProcessInstance.getBusinessKey());
			if (businessKey.length > 1) {
				flow.setBusinessTable(businessKey[0]);
				flow.setBusinessId(businessKey[1]);
			}
			flow.setHistoryActivityName(historicProcessInstance.getName());
			flow.setProcessInstanceId(historicProcessInstance.getId());
			flow.setHistoryProcessInstanceId(historicProcessInstance.getId());
			/* 流程定义信息 */
			ProcessDefinition processDefinition = FlowCache.getProcessDefinition(historicProcessInstance.getProcessDefinitionId());
			flow.setProcessDefinitionId(processDefinition.getId());
			flow.setProcessDefinitionName(processDefinition.getName());
			flow.setProcessDefinitionVersion(processDefinition.getVersion());
			flow.setProcessDefinitionKey(processDefinition.getKey());
			flow.setCategory(processDefinition.getCategory());
			flow.setCategoryName(FlowCache.getCategoryName(processDefinition.getCategory()));
			flow.setProcessInstanceId(historicProcessInstance.getId());
			/* HistoricTaskInstance */
			List<HistoricTaskInstance> historyTasks = historyService.createHistoricTaskInstanceQuery().processInstanceId(historicProcessInstance.getId()).orderByHistoricTaskInstanceEndTime().desc().list();
			if (Func.isNotEmpty(historyTasks)) {
				HistoricTaskInstance historyTask = historyTasks.iterator().next();
				flow.setTaskId(historyTask.getId());
				flow.setTaskName(historyTask.getName());
				flow.setTaskDefinitionKey(historyTask.getTaskDefinitionKey());
			}
			/* Status */
			if (Func.isNotEmpty(historicProcessInstance.getEndActivityId())) {
				flow.setProcessIsFinished(FlowEngineConstant.STATUS_FINISHED);
			} else {
				flow.setProcessIsFinished(FlowEngineConstant.STATUS_UNFINISHED);
			}
			flow.setStatus(FlowEngineConstant.STATUS_FINISH);
			flowList.add(flow);
		});

		/* 计算总数 */
		long count = historyQuery.count();
		page.setTotal(count);
		page.setRecords(flowList);
		return page;
	}

	@Override
	public IPage<BladeFlow> selectDonePage(IPage<BladeFlow> page, BladeFlow bladeFlow) {
		String taskUser = TaskUtil.getTaskUser();
		List<BladeFlow> flowList = new LinkedList<>();
		HistoricTaskInstanceQuery doneQuery = historyService.createHistoricTaskInstanceQuery().taskAssignee(taskUser).finished()
			.includeProcessVariables().orderByHistoricTaskInstanceEndTime().desc();
		if (Func.isNotEmpty(bladeFlow.getCategory())) {
			doneQuery.processCategoryIn(Func.toStrList(bladeFlow.getCategory()));
		}
		if (Func.isNotEmpty(bladeFlow.getBeginDate())) {
			doneQuery.taskCompletedAfter(bladeFlow.getBeginDate());
		}
		if (Func.isNotEmpty(bladeFlow.getEndDate())) {
			doneQuery.taskCompletedBefore(bladeFlow.getEndDate());
		}

		/* 查询列表 */
		List<HistoricTaskInstance> doneList = doneQuery.listPage(Func.toInt((page.getCurrent() - 1) * page.getSize()), Func.toInt(page.getSize()));
		doneList.forEach(historicTaskInstance -> {
			BladeFlow flow = new BladeFlow();
			flow.setTaskId(historicTaskInstance.getId());
			flow.setTaskDefinitionKey(historicTaskInstance.getTaskDefinitionKey());
			flow.setTaskName(historicTaskInstance.getName());
			flow.setAssignee(historicTaskInstance.getAssignee());
			flow.setCreateTime(historicTaskInstance.getCreateTime());
			flow.setExecutionId(historicTaskInstance.getExecutionId());
			flow.setHistoryTaskEndTime(historicTaskInstance.getEndTime());
			flow.setVariables(historicTaskInstance.getProcessVariables());

			ProcessDefinition processDefinition = FlowCache.getProcessDefinition(historicTaskInstance.getProcessDefinitionId());
			flow.setProcessDefinitionId(processDefinition.getId());
			flow.setProcessDefinitionName(processDefinition.getName());
			flow.setProcessDefinitionKey(processDefinition.getKey());
			flow.setProcessDefinitionVersion(processDefinition.getVersion());
			flow.setCategory(processDefinition.getCategory());
			flow.setCategoryName(FlowCache.getCategoryName(processDefinition.getCategory()));

			flow.setProcessInstanceId(historicTaskInstance.getProcessInstanceId());
			flow.setHistoryProcessInstanceId(historicTaskInstance.getProcessInstanceId());
			HistoricProcessInstance historicProcessInstance = getHistoricProcessInstance((historicTaskInstance.getProcessInstanceId()));
			if (Func.isNotEmpty(historicProcessInstance)) {
				String[] businessKey = Func.toStrArray(StringPool.COLON, historicProcessInstance.getBusinessKey());
				flow.setBusinessTable(businessKey[0]);
				flow.setBusinessId(businessKey[1]);
				if (Func.isNotEmpty(historicProcessInstance.getEndActivityId())) {
					flow.setProcessIsFinished(FlowEngineConstant.STATUS_FINISHED);
				} else {
					flow.setProcessIsFinished(FlowEngineConstant.STATUS_UNFINISHED);
				}
			}
			flow.setStatus(FlowEngineConstant.STATUS_FINISH);
			flowList.add(flow);
		});
		/* 计算总数 */
		long count = doneQuery.count();
		page.setTotal(count);
		page.setRecords(flowList);
		return page;
	}

	@Override
	public IPage<BladeFlow> selectDoBackPage(IPage<BladeFlow> page, BladeFlow bladeFlow) {


		return page;
	}

	/**
	 * 构建流程
	 *
	 * @param bladeFlow 流程通用类
	 * @param flowList  流程列表
	 * @param taskQuery 任务查询类
	 * @param status    状态
	 */
	private void buildFlowTaskList(BladeFlow bladeFlow, List<BladeFlow> flowList, TaskQuery taskQuery, String status) {
		if (Func.isNotEmpty(bladeFlow.getCategory())) {
			taskQuery.processCategoryIn(Func.toStrList(bladeFlow.getCategory()));
		}
		if (Func.isNotEmpty(bladeFlow.getBeginDate())) {
			taskQuery.taskCreatedAfter(bladeFlow.getBeginDate());
		}
		if (Func.isNotEmpty(bladeFlow.getEndDate())) {
			taskQuery.taskCreatedBefore(bladeFlow.getEndDate());
		}
		taskQuery.list().forEach(task -> {
			BladeFlow flow = new BladeFlow();
			flow.setTaskId(task.getId());
			flow.setTaskDefinitionKey(task.getTaskDefinitionKey());
			flow.setTaskName(task.getName());
			flow.setAssignee(task.getAssignee());
			flow.setCreateTime(task.getCreateTime());
			flow.setClaimTime(task.getClaimTime());
			flow.setExecutionId(task.getExecutionId());
			flow.setVariables(task.getProcessVariables());

			HistoricProcessInstance historicProcessInstance = getHistoricProcessInstance(task.getProcessInstanceId());
			if (Func.isNotEmpty(historicProcessInstance)) {
				String[] businessKey = Func.toStrArray(StringPool.COLON, historicProcessInstance.getBusinessKey());
				flow.setBusinessTable(businessKey[0]);
				flow.setBusinessId(businessKey[1]);
			}

			ProcessDefinition processDefinition = FlowCache.getProcessDefinition(task.getProcessDefinitionId());
			flow.setCategory(processDefinition.getCategory());
			flow.setCategoryName(FlowCache.getCategoryName(processDefinition.getCategory()));
			flow.setProcessDefinitionId(processDefinition.getId());
			flow.setProcessDefinitionName(processDefinition.getName());
			flow.setProcessDefinitionKey(processDefinition.getKey());
			flow.setProcessDefinitionVersion(processDefinition.getVersion());
			flow.setProcessInstanceId(task.getProcessInstanceId());
			flow.setStatus(status);
			flowList.add(flow);
		});
	}

	/**
	 * 获取历史流程
	 *
	 * @param processInstanceId 流程实例id
	 * @return HistoricProcessInstance
	 */
	private HistoricProcessInstance getHistoricProcessInstance(String processInstanceId) {
		return historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
	}

	@Override
	public List<FlowNodeRequest> backNodes(String taskId) {
		TaskEntity taskEntity = (TaskEntity) taskService.createTaskQuery().taskId(taskId).singleResult();
		String processInstanceId = taskEntity.getProcessInstanceId();
		String currActId = taskEntity.getTaskDefinitionKey();
		String processDefinitionId = taskEntity.getProcessDefinitionId();
		Process process = repositoryService.getBpmnModel(processDefinitionId).getMainProcess();
		FlowNode currentFlowElement = (FlowNode) process.getFlowElement(currActId, true);
		List<ActivityInstance> activitys = runtimeService.createActivityInstanceQuery()
			.processInstanceId(processInstanceId).finished().orderByActivityInstanceStartTime().asc().list();
		List<String> activityIds = activitys.stream()
			.filter(activity -> activity.getActivityType().equals(BpmnXMLConstants.ELEMENT_TASK_USER))
			.filter(activity -> !activity.getActivityId().equals(currActId)).map(ActivityInstance::getActivityId)
			.distinct().collect(Collectors.toList());
		List<FlowNodeRequest> result = new ArrayList<>();
		for (String activityId : activityIds) {
			FlowNode toBackFlowElement = (FlowNode) process.getFlowElement(activityId, true);
			if (FlowableUtils.isReachable(process, toBackFlowElement, currentFlowElement)) {
				FlowNodeRequest vo = FlowNodeRequest.builder()
					.id(activityId)
					.name(toBackFlowElement.getName())
					.build();
				result.add(vo);
			}
		}
		return result;
	}

	@Override
	public boolean backTask(TaskRequest taskRequest) {
		String taskId = taskRequest.getTaskId();
		/* 获取当前登录人 */
		BladeUser user = AuthUtil.getUser();
		String userId = String.valueOf(user.getUserId());
		Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
		String backSysMessage = "退回到" + taskRequest.getNodeName() + "。";
		this.addComment(taskId, task.getProcessInstanceId(), userId, CommentTypeEnum.TH,
			backSysMessage + taskRequest.getMessage());
		String targetRealActivityId = managementService.executeCommand(
			new BackUserTaskCmd(runtimeService, taskRequest.getTaskId(), taskRequest.getNodeId()));
		return true;
	}

	@Override
	public void addComment(String taskId, String processInstanceId, String userId, CommentTypeEnum type,
						   String message) {
		Authentication.setAuthenticatedUserId(userId);
		type = type == null ? CommentTypeEnum.SP : type;
		message = (message == null || message.length() == 0) ? type.getName() : message;
		taskService.addComment(taskId, processInstanceId, type.toString(), message);
	}

	/**
	 * 获取当前登录人id
	 */
	private String getLoginId() {
		BladeUser user = AuthUtil.getUser();
		String userId = String.valueOf(user.getUserId());
		return userId;
	}

	@Override
	public void cancelTask(BladeFlow flow) {
		if (Func.isEmpty(flow.getProcessInstanceId())) {
			log.error("【错误码{}】：流程实例id为空", ServiceCode.FLOW_INSTANCE_ID_NOT_FOUND.getCode());
			throw new ServiceException(ServiceCode.FLOW_INSTANCE_ID_NOT_FOUND);
		}
		runtimeService.deleteProcessInstance(flow.getProcessInstanceId(), "终止原因");
	}


	@Override
	public void assignTask(BladeFlow flow) {
		/* 任务id */
		String taskId = flow.getTaskId();
		/* 转办人员id */
		String assignee = TaskUtil.getTaskUser(flow.getAssignee());
		/* 当前人员id */
		String userId = this.getLoginId();
		/* 创建子任务先添加审批意见再完成子任务 */
		TaskEntity taskEntity = (TaskEntity) taskService.createTaskQuery().taskId(taskId).singleResult();
		TaskEntity task = this.createSubTask(taskEntity, TaskUtil.getTaskUser(userId));
		this.addComment(task.getId(), task.getProcessInstanceId(), userId, CommentTypeEnum.ZB, flow.getComment());
		taskService.complete(task.getId());
		/* 执行转办 */
		taskService.setAssignee(taskId, assignee);
	}

	@Override
	public void delegateTask(BladeFlow flow) {
		/* 任务id */
		String taskId = flow.getTaskId();
		/* 委托人员id */
		String assignee = TaskUtil.getTaskUser(flow.getAssignee());
		/* 当前登录人员id */
		String userId = this.getLoginId();
		/* 是否可以委派任务，如果不可以的话直接被异常处理拦截 */
		Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
		this.addComment(taskId, task.getProcessInstanceId(), userId, CommentTypeEnum.WP, flow.getComment());
		/* 当前审核人为当前登录人 */
		taskService.setAssignee(taskId, userId);
		/* 执行转办 */
		taskService.delegateTask(task.getId(), assignee);
	}

	@Override
	public boolean delegateBack(BladeFlow flow) {
		/* 任务id */
		String taskId = flow.getTaskId();
		/* 当前人员id */
		String userId = this.getLoginId();
		TaskEntity taskEntity = (TaskEntity) taskService.createTaskQuery().taskId(taskId).singleResult();
		/* 创建子任务 */
		TaskEntity task = this.createSubTask(taskEntity, TaskUtil.getTaskUser(userId));
		/* 生成历史记录 */
		this.addComment(task.getId(), flow.getProcessInstanceId(), userId, CommentTypeEnum.WPFH, flow.getComment());
		taskService.complete(task.getId());
		/* 被委派人把任务返回给委派人 */
		taskService.resolveTask(taskId);
		return true;
	}


	@Override
	public boolean takeItBackTask(BladeFlow flow) {
		TaskEntity taskEntity = (TaskEntity) taskService.createTaskQuery().taskId(flow.getTaskId()).singleResult();
		String submitter = "提交人";
		/* 1.把当前的节点设置为空 */
		if (Func.isNotEmpty(taskEntity)) {
			/* 2.设置审批人 */
			taskEntity.setAssignee(flow.getAssignee());
			taskService.saveTask(taskEntity);
			/* 3.添加退回意见 */
			String userId = this.getLoginId();
			this.addComment(flow.getTaskId(), taskEntity.getProcessInstanceId(), userId, CommentTypeEnum.TH, flow.getComment());
			/* 4.处理提交人节点（对发起人进行特殊处理） */
			FlowNode activity = null;
			BpmnModel bpmnModel = this.repositoryService.getBpmnModel(taskEntity.getProcessDefinitionId());
			List<Process> processes = bpmnModel.getProcesses();
			for (Process process : processes) {
				/* 需要退回节点的id */
				FlowElement flowElement = process.getFlowElementMap().get(flow.getDistFlowElementId());
				if (Func.isNotEmpty(flowElement)) {
					activity = (FlowNode) flowElement;
					break;
				}
			}
			FlowNode distActivity = activity;
			if (Func.isNotEmpty(distActivity)) {
				if (submitter.equals(distActivity.getName())) {
					ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(taskEntity.getProcessInstanceId()).singleResult();
					runtimeService.setVariable(flow.getProcessInstanceId(), "initiator", processInstance.getStartUserId());
				}
			}
			/* 5.删除节点 */
			this.deleteActivity(flow.getDistFlowElementId(), taskEntity.getProcessInstanceId());
			List<String> executionIds = new ArrayList<>();
			/* 6.判断节点是不是子流程内部的节点 */
			List<Execution> executions = runtimeService.createExecutionQuery().parentId(taskEntity.getProcessInstanceId()).list();
			executions.forEach(execution -> executionIds.add(execution.getId()));
			runtimeService.createChangeActivityStateBuilder().moveExecutionsToSingleActivityId(executionIds, flow.getDistFlowElementId()).changeState();
			return true;
		} else {
			return false;
		}
	}


	@Override
	public List<FlowNodeVo> takeItBackTaskLook(BladeFlow flow) {
		List<FlowNodeVo> backNods = new ArrayList<>();
		/* 当前任务的节点 */
		TaskEntity taskEntity = (TaskEntity) taskService.createTaskQuery().taskId(flow.getTaskId()).singleResult();
		/* 得到当前任务的key */
		String currActId = taskEntity.getTaskDefinitionKey();
		/* 获取运行节点表中usertask（已经审完的节点） */
		String sql = "select t.* from act_ru_actinst t where t.ACT_TYPE_ = 'userTask' and t.PROC_INST_ID_=#{processInstanceId} and t.END_TIME_ is not null GROUP BY t.act_id_";
		List<ActivityInstance> activityInstances = runtimeService.createNativeActivityInstanceQuery().sql(sql)
			.parameter("processInstanceId", flow.getProcessInstanceId())
			.list();
		/* 获取运行节点表的parallelGateway节点并出重 */
		sql = "SELECT t.ID_, t.REV_,t.PROC_DEF_ID_,t.PROC_INST_ID_,t.EXECUTION_ID_,t.ACT_ID_, t.TASK_ID_, t.CALL_PROC_INST_ID_, t.ACT_NAME_, t.ACT_TYPE_, " +
			" t.ASSIGNEE_, t.START_TIME_, max(t.END_TIME_) as END_TIME_, t.DURATION_, t.DELETE_REASON_, t.TENANT_ID_" +
			" FROM  act_ru_actinst t WHERE t.ACT_TYPE_ = #{actType} AND t.PROC_INST_ID_ = #{processInstanceId} and t.END_TIME_ is not null" +
			" and t.ACT_ID_ <> #{actId} GROUP BY t.act_id_";
		List<ActivityInstance> parallelGatewaies = runtimeService.createNativeActivityInstanceQuery().sql(sql)
			.parameter("actType", "parallelGateway")
			.parameter("processInstanceId", flow.getProcessInstanceId())
			.parameter("actId", currActId)
			.list();
		/* 查询包容网关节点并出重 */
		List<ActivityInstance> inclusiveGateways = runtimeService.createNativeActivityInstanceQuery().sql(sql)
			.parameter("actType", "inclusiveGateway")
			.parameter("processInstanceId", flow.getProcessInstanceId())
			.parameter("actId", currActId)
			.list();
		/* 排序 */
		if (CollectionUtils.isNotEmpty(parallelGatewaies)) {
			activityInstances.addAll(parallelGatewaies);
			activityInstances.sort(Comparator.comparing(ActivityInstance::getEndTime));
		}
		if (CollectionUtils.isNotEmpty(inclusiveGateways)) {
			activityInstances.addAll(inclusiveGateways);
			activityInstances.sort(Comparator.comparing(ActivityInstance::getEndTime));
		}
		int group = 0;
		if (BpmnXMLConstants.ELEMENT_GATEWAY_PARALLEL.equals(activityInstances.get(activityInstances.size() - 1).getActivityType()) || BpmnXMLConstants.ELEMENT_GATEWAY_INCLUSIVE.equals(activityInstances.get(activityInstances.size() - 1).getActivityType())) {
			if ((parallelGatewaies.size() % group != 0) || (inclusiveGateways.size() % group != 0)) {
				activityInstances.remove(activityInstances.size() - 1);
			}
		}
		/* 分组节点 */
		int count = 0;
		int sun = 0;
		String type = null;
		Map<ActivityInstance, List<ActivityInstance>> parallelGatewayUserTasks = new HashMap<>(16);
		Map<ActivityInstance, List<ActivityInstance>> inclusiveGatewayUserTasks = new HashMap<>(16);
		List<ActivityInstance> userTasks = new ArrayList<>();
		ActivityInstance currActivityInstance = null;
		for (ActivityInstance activityInstance : activityInstances) {
			sun++;
			if (BpmnXMLConstants.ELEMENT_GATEWAY_PARALLEL.equals(activityInstance.getActivityType())) {
				count++;
				type = "parallelGateway";
				if (count % 2 != 0) {
					List<ActivityInstance> datas = new ArrayList<>();
					currActivityInstance = activityInstance;
					parallelGatewayUserTasks.put(currActivityInstance, datas);
				}
			}
			if (BpmnXMLConstants.ELEMENT_GATEWAY_INCLUSIVE.equals(activityInstance.getActivityType())) {
				count++;
				type = "inclusiveGateway";
				if (count % 2 != 0) {
					List<ActivityInstance> datas = new ArrayList<>();
					currActivityInstance = activityInstance;
					inclusiveGatewayUserTasks.put(currActivityInstance, datas);
				}
			}
			if (BpmnXMLConstants.ELEMENT_TASK_USER.equals(activityInstance.getActivityType())) {
				if (count % 2 == 0) {
					userTasks.add(activityInstance);
				} else {
					if (BpmnXMLConstants.ELEMENT_GATEWAY_PARALLEL.equals(type) || parallelGatewayUserTasks.containsKey(currActivityInstance)) {
						parallelGatewayUserTasks.get(currActivityInstance).add(activityInstance);
					}
					if (BpmnXMLConstants.ELEMENT_GATEWAY_INCLUSIVE.equals(type) || inclusiveGatewayUserTasks.containsKey(currActivityInstance)) {
						inclusiveGatewayUserTasks.get(currActivityInstance).add(activityInstance);
					}
					if (activityInstances.size() == sun) {
						parallelGatewayUserTasks.remove(currActivityInstance);
						inclusiveGatewayUserTasks.remove(currActivityInstance);
					}
				}
			}
		}
		/* 组装人员名称 查询审批通过节点的历史 */
		List<HistoricTaskInstance> historicTaskInstances = historyService.createHistoricTaskInstanceQuery()
			.processInstanceId(flow.getProcessInstanceId()).finished().list();
		Map<String, List<HistoricTaskInstance>> taskInstanceMap = new HashMap<>(16);
		List<String> userCodes = new ArrayList<>();
		historicTaskInstances.forEach(historicTaskInstance -> {
			userCodes.add(historicTaskInstance.getAssignee());
			String taskDefinitionKey = historicTaskInstance.getTaskDefinitionKey();
			if (taskInstanceMap.containsKey(historicTaskInstance.getTaskDefinitionKey())) {
				taskInstanceMap.get(taskDefinitionKey).add(historicTaskInstance);
			} else {
				List<HistoricTaskInstance> tasks = new ArrayList<>();
				tasks.add(historicTaskInstance);
				taskInstanceMap.put(taskDefinitionKey, tasks);
			}
		});
		/* 组装usertask的数据 */
		if (CollectionUtils.isNotEmpty(userTasks)) {
			userTasks.forEach(activityInstance -> {
				FlowNodeVo node = new FlowNodeVo();
				node.setNodeId(activityInstance.getActivityId());
				node.setNodeName(activityInstance.getActivityName());
				node.setEndTime(activityInstance.getEndTime());
				node.setUserName("");
				backNods.add(node);
			});
		}
		if (!taskInstanceMap.isEmpty()) {
			/* 组装并行网关会签节点数据 */
			parallelGatewayUserTasks.forEach((activity, activities) -> {
				FlowNodeVo node = new FlowNodeVo();
				node.setNodeId(activity.getActivityId());
				node.setEndTime(activity.getEndTime());
				StringBuffer nodeNames = new StringBuffer("会签:");
				StringBuffer userNames = new StringBuffer("审批人员:");
				node.setNodeName(nodeNames.toString());
				node.setUserName(userNames.toString());
				backNods.add(node);
			});
			/* 组装包容网关会签节点数据 */
			inclusiveGatewayUserTasks.forEach((activity, activities) -> {
				FlowNodeVo node = new FlowNodeVo();
				node.setNodeId(activity.getActivityId());
				node.setEndTime(activity.getEndTime());
				StringBuffer nodeNames = new StringBuffer("会签:");
				StringBuffer userNames = new StringBuffer("审批人员:");
				node.setNodeName(nodeNames.toString());
				node.setUserName(userNames.toString());
				backNods.add(node);
			});
		}
		/* 去重合并 */
		List<FlowNodeVo> datas = backNods.stream().collect(
			Collectors.collectingAndThen(Collectors.toCollection(() ->
				new TreeSet<>(Comparator.comparing(nodeVo -> nodeVo.getNodeId()))), ArrayList::new));
		/* 排序 */
		datas.sort(Comparator.comparing(FlowNodeVo::getEndTime));
		return datas;
	}

	@Override
	public boolean takeBackTask(BladeFlow flow) {
		/* 拿回的条件是下一节点不能审批，需要查询出发出的任务，下一节点是否审批了 */
		TaskEntity taskEntity = (TaskEntity) taskService.createTaskQuery().taskId(flow.getTaskId()).singleResult();
		String sqlTask = "select t.* from act_ru_actinst t where t.ACT_TYPE_ = 'userTask' " +
			" and t.PROC_INST_ID_=#{processInstanceId} and t.TASK_ID_=#{taskId}";
		List<ActivityInstance> activity = runtimeService.createNativeActivityInstanceQuery().sql(sqlTask)
			.parameter("processInstanceId", flow.getProcessInstanceId())
			.parameter("taskId", flow.getTaskId())
			.list();

		String sql = "select t.* from act_ru_actinst t where t.ACT_TYPE_ = 'userTask' " +
			" and t.PROC_INST_ID_=#{processInstanceId} and t.END_TIME_ >#{endTime}  ";
		List<ActivityInstance> activitys = runtimeService.createNativeActivityInstanceQuery().sql(sql)
			.parameter("processInstanceId", flow.getProcessInstanceId())
			.parameter("endTime", activity.get(0).getEndTime())
			.list();
		if (activitys.size() > 0) {
			System.out.println("下一节点已审批，不可拿回");
			return false;
		} else {
			String sqlPend = "select t.* from act_ru_actinst t where " +
				"t.PROC_INST_ID_=#{processInstanceId} and t.END_TIME_ is null  ";
			List<ActivityInstance> activityss = runtimeService.createNativeActivityInstanceQuery().sql(sqlPend)
				.parameter("processInstanceId", flow.getProcessInstanceId())
				.list();
			if (CollectionUtils.isNotEmpty(activityss)) {
				/* 删除对应的运行时的节点信息和历史的节点信息 */
				for (ActivityInstance ac : activityss) {
					runFlowableActinstDao.deleteRunActinstsById(ac.getId());
					hisFlowableActinstDao.deleteHisActinstsById(ac.getId());
				}
			}
			/* 6.2 拿回 */
			List<String> executionIds = new ArrayList<>();
			List<Execution> executions = runtimeService.createExecutionQuery().parentId(flow.getProcessInstanceId()).list();
			executions.forEach(execution -> executionIds.add(execution.getId()));
			runtimeService.createChangeActivityStateBuilder().moveExecutionsToSingleActivityId(executionIds, activity.get(0).getActivityId()).changeState();
			System.out.println("拿回成功");
			return true;
		}
	}

	@Override
	public String btnPermission(String taskId) {
		/* 获取taskEntity对象 */
		TaskEntity taskEntity = (TaskEntity) taskService.createTaskQuery().taskId(taskId).singleResult();
		/* 当前节点id */
		String currActId = taskEntity.getTaskDefinitionKey();
		/* 流程定义id */
		String processDefinitionId = taskEntity.getProcessDefinitionId();
		/* 获取流程实例对象 */
		Process process = repositoryService.getBpmnModel(processDefinitionId).getMainProcess();
		/* 获取当前节点 */
		FlowNode targetNode = (FlowNode) process.getFlowElement(currActId, true);
		/* 获取btnPermission标签值*/
		String attributeValue = targetNode.getAttributeValue(FlowEngineConstant.NAME_SPACE, FlowEngineConstant.BTN_PERMISSION);
		/* 把ASCII码转为符号 */
		return attributeValue.replaceAll("&#34;", "'");
	}

	/**
	 * 删除跳转的历史节点信息
	 *
	 * @param disActivityId     跳转的节点id
	 * @param processInstanceId 流程实例id
	 */
	protected void deleteActivity(String disActivityId, String processInstanceId) {

		String tableName = managementService.getTableName(ActivityInstanceEntity.class);
		String sql = "select t.* from " + tableName + " t where t.PROC_INST_ID_=#{processInstanceId} and t.ACT_ID_ = #{disActivityId} " +
			" order by t.END_TIME_ ASC";
		/* 查询该实例将要删除的节点 */
		List<ActivityInstance> disActivities = runtimeService.createNativeActivityInstanceQuery().sql(sql)
			.parameter("processInstanceId", processInstanceId)
			.parameter("disActivityId", disActivityId).list();
		/* 删除运行时和历史节点信息 */
		if (CollectionUtils.isNotEmpty(disActivities)) {
			ActivityInstance activityInstance = disActivities.get(0);
			sql = "select t.* from " + tableName + " t where t.PROC_INST_ID_=#{processInstanceId} and (t.END_TIME_ >= #{endTime} or t.END_TIME_ is null)";
			/* 查询出该实例走过的每个节点和连线 */
			List<ActivityInstance> datas = runtimeService.createNativeActivityInstanceQuery().sql(sql).parameter("processInstanceId", processInstanceId)
				.parameter("endTime", activityInstance.getEndTime()).list();
			if (CollectionUtils.isNotEmpty(datas)) {
				/* 删除对应的运行时的节点信息和历史的节点信息 */
				for (ActivityInstance ac : datas) {
					runFlowableActinstDao.deleteRunActinstsById(ac.getId());
					hisFlowableActinstDao.deleteHisActinstsById(ac.getId());
				}
			}
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public BladeFlow startProcessInstanceById(FlowNodeRequest flowNodeRequest, String businessKey) {
		String processDefinitionId = flowNodeRequest.getProcessDefinitionId();
		Map<String, Object> variables = flowNodeRequest.getVariables();
		/* 设置流程启动用户 */
		identityService.setAuthenticatedUserId(TaskUtil.getTaskUser());
		/* 获取主流程开始节点 */
		FlowNode startEvent = (FlowNode) repositoryService.getBpmnModel(processDefinitionId)
			.getMainProcess().getFlowElements()
			.stream()
			.filter(flowElement -> flowElement instanceof StartEvent)
			.limit(1)
			.collect(Collectors.toList()).get(0);
		/* 获取第一个用户节点id，即起草人节点，开始节点之后有且仅有一个用户节点 */
		FlowNode firstNode = (FlowNode) startEvent.getOutgoingFlows().get(0).getTargetFlowElement();
		/* 获取第一个用户节点id，自动提交流程 */
		String firstId = firstNode.getId();
		/* 开启流程，给第一个用户节点赋空值，否则流程无法提交，此处无法设置办理人，后续会设置 */
		variables.put(firstId, "");
		ProcessInstance processInstance = runtimeService.startProcessInstanceById(processDefinitionId, businessKey, variables);
		String processInstanceId = processInstance.getProcessInstanceId();
		List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
		tasks.forEach(task -> {
			/* 第一个用户节点为起草人,自动完成任务 */
			if (firstId.equals(task.getTaskDefinitionKey())) {
				this.addComment(task.getId(), processInstanceId, AuthUtil.getUserId().toString(), CommentTypeEnum.TJ, null);
				/*给第一个用户节点设置办理人为当前登录人，即流程发起者*/
				taskService.setAssignee(task.getId(), AuthUtil.getUserId().toString());
				/*查询第一个用户节点后边的节点（可能是用户节点或网关）*/
				FlowNode secondNode = (FlowNode) firstNode.getOutgoingFlows().get(0).getTargetFlowElement();
				/*此时secondNode是用户节点*/
				if (secondNode instanceof UserTask) {
					if (CollectionUtil.isEmpty(flowNodeRequest.getUserResponseList())) {
						log.error("【错误码{}】：流程启动最终确认时未获取到下一办理人信息，请检查前台传参是否正确", ServiceCode.FLOW_NEXT_USER_NOT_FOUND.getCode());
						throw new ServiceException(ServiceCode.FLOW_NEXT_USER_NOT_FOUND);
					}
					/* 通过全局变量设置办理人 */
					variables.put(secondNode.getId(), TaskUtil.getTaskUser(flowNodeRequest.getUserResponseList().get(0).getId()));
				}
				/*此时secondNode是网关节点*/
				if (secondNode instanceof Gateway) {
					secondNode.getOutgoingFlows().forEach(sequenceFlow -> {
						/*网关连接的每一个用户节点，此处不判断流程将要提交给哪些节点，将所有节点候选人都设置到全局变量*/
						FlowNode secondUserNode = (FlowNode) sequenceFlow.getTargetFlowElement();
						if (CollectionUtil.isEmpty(flowNodeRequest.getUserResponseList())) {
							log.error("【错误码{}】：流程启动最终确认时未获取到下一办理人信息，请检查前台传参是否正确", ServiceCode.FLOW_NEXT_USER_NOT_FOUND.getCode());
							throw new ServiceException(ServiceCode.FLOW_NEXT_USER_NOT_FOUND);
						}
						/* 通过全局变量设置办理人 */
						variables.put(secondUserNode.getId(), TaskUtil.getTaskUser(flowNodeRequest.getUserResponseList().get(0).getId()));
					});
				}
				taskService.complete(task.getId(), variables);
			}
		});
		/* 组装流程通用类 */
		BladeFlow flow = new BladeFlow();
		flow.setProcessInstanceId(processInstance.getId());
		return flow;
	}

	@Override
	public List<FlowNodeRequest> startProcessBefore(Map<String, Object> maps, String businessType) {
		/* 当前登录人的部门id */
		String currentUserDeptId = AuthUtil.getDeptId();
		/*根据业务类型获取满足条件的流程xml*/
		List<ProcessEntity> processEntityList = processService.getProcessByBusinessType(businessType);
		List<FlowNodeRequest> flowNodeRequests = new ArrayList<>();
		if (Func.isNotEmpty(processEntityList)) {
			/*获取当前登录人部门的祖籍列表*/
			String deptAncestors = "";
			R<Dept> dept = sysClient.getDept(Long.parseLong(currentUserDeptId));
			if (dept.isSuccess()) {
				/*拼接当前部门id*/
				deptAncestors = StringUtil.format("{},{}",dept.getData().getAncestors(),currentUserDeptId);
			} else {
				log.error("【错误码{}】：blade-flow调用blade-system出错，请查看blade-system模块异常信息",ServiceCode.FEIGN_FAIL.getCode());
				throw new ServiceException(ServiceCode.FEIGN_FAIL);
			}
			List<String> deptIds = Arrays.asList(deptAncestors.split(","));
			/*用于存储每个候选流程的使用范围id，用于和当前登录人部门去匹配*/
			List<String> processDefineIds = new ArrayList<>();
			/*取出每一个流程使用范围id*/
			processEntityList.forEach(processEntity -> processDefineIds.add(processEntity.getDeptRange().toString()));
			/*先倒序祖籍列表，从最底级开始依次排查是否包含在流程定义的List中，取出第一个匹配的，即最小级的部门id*/
			List<String> deptIdResult = deptIds.stream().sorted(Comparator.reverseOrder()).filter(processDefineIds::contains).limit(1).collect(Collectors.toList());
			/*过滤流程定义List，取出与最低小级部门匹配的流程定义信息，即 即将启动的流程*/
			List<ProcessEntity> processEntityResult = processEntityList.stream().filter(processEntity -> Func.equals(deptIdResult.get(0), processEntity.getDeptRange().toString())).collect(Collectors.toList());
			/*计算表达式*/
			ScriptEngineManager manager = new ScriptEngineManager();
			ScriptEngine engine = manager.getEngineByName("js");
			/*过滤流程启动条件*/
			processEntityResult = processEntityResult.stream().filter(processEntity -> {
				if (Func.isNotEmpty(processEntity.getStartCondition())) {
					String condition = processEntity.getStartCondition();
					boolean flag;
					for (Map.Entry<String, Object> map : maps.entrySet()) {
						if (condition.contains(map.getKey())) {
							condition = condition.replace(map.getKey(), map.getValue().toString());
						}
					}
					try {
						flag = (boolean) engine.eval(condition);
					} catch (ScriptException e) {
						log.error("【错误码{}】：JS表达式判断出现异常，表达式为【{}】", ServiceCode.FLOW_SCRIPT_ENGINE_EXCEPTION.getCode(), condition, e);
						throw new ServiceException(ServiceCode.FLOW_SCRIPT_ENGINE_EXCEPTION);
					}
					return flag;
				} else {
					/*如果启动条件为空说明不涉及启动条件，则保留该条数据*/
					return true;
				}
			}).collect(Collectors.toList());
			if (Func.isNotEmpty(processEntityResult)) {
				ProcessEntity processEntity = processEntityResult.get(0);
				/*获取主流程*/
				Process mainProcess = repositoryService.getBpmnModel(processEntityResult.get(0).getProcessDefinitionId()).getMainProcess();
				/*定义全局变量*/
				Kv variables = Kv.create();
				/*获取流程全局变量参数，逗号分隔*/
				String params = processEntity.getParams();
				if (Func.isNotEmpty(params)) {
					/*通过流程全局参数名获取value给全局变量赋值*/
					Arrays.asList(params.split(",")).forEach(param -> variables.put(param, maps.get(param)));
				}
				/* 获取主流程开始节点 */
				FlowNode startEvent = (FlowNode) mainProcess.getFlowElements().stream().filter(flowElement -> flowElement instanceof StartEvent).limit(1).collect(Collectors.toList()).get(0);
				/* 获取第一个用户节点id，即起草人节点，开始节点之后有且仅有一个用户节点 */
				FlowNode firstNode = (FlowNode) startEvent.getOutgoingFlows().get(0).getTargetFlowElement();
				/*获取承办人节点后边的节点（可能是用户节点或网关）*/
				FlowNode secondNode = (FlowNode) firstNode.getOutgoingFlows().get(0).getTargetFlowElement();
				if (secondNode instanceof UserTask) {
					List<FlowUserRequest> candidateUsers = this.getCandidateUsers(secondNode);
					FlowNodeRequest flowNodeRequest = FlowNodeRequest.builder()
						.userResponseList(candidateUsers)
						.id(secondNode.getId())
						.processDefinitionId(processEntity.getProcessDefinitionId())
						.name(secondNode.getName())
						.variables(variables)
						.enableChooseNode(false)
						.end(false)
						.build();
					flowNodeRequests.add(flowNodeRequest);
				}
				/*如果secondNode是网关节点*/
				if (secondNode instanceof Gateway) {
					// 排他网关
					if (secondNode instanceof ExclusiveGateway) {
						/* 遍历所有连线，确定流程即将进入的连线 */
						for (SequenceFlow outLine : secondNode.getOutgoingFlows()) {
							/* 获取连线的条件 */
							String conditionExpression = outLine.getConditionExpression();
							/* 去掉$、{、}， */
							conditionExpression = conditionExpression.replace("$", "")
								.replace("{", "")
								.replace("}", "");
							/* 遍历全局变量，将全局变量中与条件表达式对应的key替换为全局变量的value */
							/* 得到类似：value（全局变量） == value（连线表达式） 的字符串表达式 */
							for (Map.Entry<String, Object> map : variables.entrySet()) {
								conditionExpression = conditionExpression.replace(map.getKey(), "'" + map.getValue().toString() + "'");
							}
							/* 调用js对字符串表达式进行计算，可以计算数字和字符串：1==1返回true，'a'=='a'返回true */
							try {
								boolean result = (boolean) engine.eval(conditionExpression);
								if (result) {
									FlowNode flowNodeExclusive = (FlowNode) outLine.getTargetFlowElement();
									/* 获取节点自定义属性 */
									List<FlowUserRequest> candidateUserList = this.getCandidateUsers(flowNodeExclusive);
									FlowNodeRequest flowNodeRequest = FlowNodeRequest.builder()
										.end(false)
										.processDefinitionId(processEntity.getProcessDefinitionId())
										.variables(variables)
										.id(flowNodeExclusive.getId())
										.name(flowNodeExclusive.getName())
										.userResponseList(candidateUserList)
										.build();
									flowNodeRequests.add(flowNodeRequest);
									/* 排他网关只能有一条线可以走，只要遇到满足条件的就结束循环 */
									break;
								}
							} catch (ScriptException e) {
								log.error("【错误码{}】：JS表达式判断出现异常，表达式为【{}】", ServiceCode.FLOW_SCRIPT_ENGINE_EXCEPTION.getCode(), conditionExpression, e);
								throw new ServiceException(ServiceCode.FLOW_SCRIPT_ENGINE_EXCEPTION);
							}
						}
					}
					/* 并行网关，所有分支必须执行，无法自由选择 */
					if (secondNode instanceof ParallelGateway) {
						/* 遍历并行网关的每一条出线获取每一个节点 */
						secondNode.getOutgoingFlows().forEach(sequenceFlow -> {
							FlowNode targetFlowNode = (FlowNode) sequenceFlow.getTargetFlowElement();
							/* 获取节点自定义属性并返回人员List */
							List<FlowUserRequest> candidateUserList = this.getCandidateUsers(targetFlowNode);
							FlowNodeRequest flowNodeRequest = FlowNodeRequest.builder()
								.id(targetFlowNode.getId())
								.name(targetFlowNode.getName())
								.processDefinitionId(processEntity.getProcessDefinitionId())
								.end(false)
								.variables(variables)
								.userResponseList(candidateUserList)
								.build();
							flowNodeRequests.add(flowNodeRequest);
						});
					}
					/* 包容网关，可以选择一条或多条出线，此处在前台展示所有可选的节点和用户信息 */
					if (secondNode instanceof InclusiveGateway) {
						secondNode.getOutgoingFlows().forEach(sequenceFlow -> {
							FlowNode targetNodeInclusive = (FlowNode) sequenceFlow.getTargetFlowElement();
							List<FlowUserRequest> candidateUserList = this.getCandidateUsers(secondNode);
							FlowNodeRequest flowNodeRequest = FlowNodeRequest.builder()
								.end(false)
								.enableChooseNode(true)
								.id(targetNodeInclusive.getId())
								.processDefinitionId(processEntity.getProcessDefinitionId())
								.name(targetNodeInclusive.getName())
								.variables(variables)
								.userResponseList(candidateUserList)
								.build();
							flowNodeRequests.add(flowNodeRequest);
						});
					}
				}
				return flowNodeRequests;
			} else {
				log.error(
					"【错误码{}】：获取流程定义信息失败，进行使用范围及启动条件过滤后，未获取到匹配的processList，当前登录人部门id={}",
					ServiceCode.FLOW_PROCESS_NOT_FOUND.getCode(),
					currentUserDeptId);
				throw new ServiceException(ServiceCode.FLOW_PROCESS_NOT_FOUND);
			}
		} else {
			log.error("【错误码{}】：获取流程定义信息失败，根据businessType未获取到processList，businessType={}",
				ServiceCode.FLOW_PROCESS_NOT_FOUND.getCode(),
				businessType);
			throw new ServiceException(ServiceCode.FLOW_PROCESS_NOT_FOUND);
		}
	}
}
