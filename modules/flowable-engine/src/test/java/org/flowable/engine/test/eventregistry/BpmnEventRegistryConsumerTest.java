/* Licensed under the Apache License, Version 2.0 (the "License");
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
package org.flowable.engine.test.eventregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.flowable.common.engine.api.constant.ReferenceTypes;
import org.flowable.common.engine.api.scope.ScopeTypes;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.test.Deployment;
import org.flowable.eventregistry.api.EventDeployment;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.api.InboundEventChannelAdapter;
import org.flowable.eventregistry.api.model.EventPayloadTypes;
import org.flowable.eventregistry.model.InboundChannelModel;
import org.flowable.eventsubscription.api.EventSubscription;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class BpmnEventRegistryConsumerTest extends FlowableEventRegistryBpmnTestCase {

    protected TestInboundEventChannelAdapter inboundEventChannelAdapter;

    @BeforeEach
    public void setUp() throws Exception {
        inboundEventChannelAdapter = setupTestChannel();

        getEventRepositoryService().createEventModelBuilder()
            .inboundChannelKey("test-channel")
            .key("myEvent")
            .resourceName("myEvent.event")
            .correlationParameter("customerId", EventPayloadTypes.STRING)
            .correlationParameter("orderId", EventPayloadTypes.STRING)
            .payload("payload1", EventPayloadTypes.STRING)
            .payload("payload2", EventPayloadTypes.INTEGER)
            .deploy();
    }
    
    protected TestInboundEventChannelAdapter setupTestChannel() {
        TestInboundEventChannelAdapter inboundEventChannelAdapter = new TestInboundEventChannelAdapter();
        getEventRegistryEngineConfiguration().getExpressionManager().getBeans()
            .put("inboundEventChannelAdapter", inboundEventChannelAdapter);

        getEventRepositoryService().createInboundChannelModelBuilder()
            .key("test-channel")
            .resourceName("testChannel.channel")
            .channelAdapter("${inboundEventChannelAdapter}")
            .jsonDeserializer()
            .detectEventKeyUsingJsonField("type")
            .jsonFieldsMapDirectlyToPayload()
            .deploy();

        return inboundEventChannelAdapter;
    }

    @AfterEach
    public void tearDown() throws Exception {
        EventRepositoryService eventRepositoryService = getEventRepositoryService();
        List<EventDeployment> deployments = eventRepositoryService.createDeploymentQuery().list();
        for (EventDeployment eventDeployment : deployments) {
            eventRepositoryService.deleteDeployment(eventDeployment.getId());
        }
    }

    @Test
    @Deployment
    public void testBoundaryEventListenerNoCorrelation() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("process");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("task");
        
        EventSubscription eventSubscription = runtimeService.createEventSubscriptionQuery().activityId("eventBoundary").singleResult();
        assertThat(eventSubscription).isNotNull();
        assertThat(eventSubscription.getProcessInstanceId()).isEqualTo(processInstance.getId());
        assertThat(eventSubscription.getEventType()).isEqualTo("myEvent");

        inboundEventChannelAdapter.triggerTestEvent();
        Task afterTask = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(afterTask.getTaskDefinitionKey()).isEqualTo("taskAfterBoundary");
        
        eventSubscription = runtimeService.createEventSubscriptionQuery().activityId("eventBoundary").singleResult();
        assertThat(eventSubscription).isNull();
    }
    
    @Test
    @Deployment
    public void testBoundaryEventListenerNoCorrelationNoTrigger() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("process");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("task");

        taskService.complete(task.getId());
        
        Task afterTask = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(afterTask.getTaskDefinitionKey()).isEqualTo("taskAfterTask");
        
        EventSubscription eventSubscription = runtimeService.createEventSubscriptionQuery().activityId("eventBoundary").singleResult();
        assertThat(eventSubscription).isNull();
    }

    @Test
    @Deployment
    public void testBoundaryEventListenerWithCorrelation() {
        Map<String, Object> variableMap = new HashMap<>();
        variableMap.put("customerIdVar", "kermit");
        ProcessInstance kermitProcessInstance = runtimeService.startProcessInstanceByKey("process", variableMap);
        
        variableMap.clear();
        variableMap.put("customerIdVar", "gonzo");
        ProcessInstance gonzoProcessInstance = runtimeService.startProcessInstanceByKey("process", variableMap);
 
        assertThat(taskService.createTaskQuery().processInstanceId(kermitProcessInstance.getId()).singleResult().getTaskDefinitionKey()).isEqualTo("task");
        assertThat(taskService.createTaskQuery().processInstanceId(gonzoProcessInstance.getId()).singleResult().getTaskDefinitionKey()).isEqualTo("task");

        inboundEventChannelAdapter.triggerTestEvent("kermit");
        assertThat(taskService.createTaskQuery().processInstanceId(kermitProcessInstance.getId()).singleResult().getTaskDefinitionKey()).isEqualTo("taskAfterBoundary");
        assertThat(taskService.createTaskQuery().processInstanceId(gonzoProcessInstance.getId()).singleResult().getTaskDefinitionKey()).isEqualTo("task");

        inboundEventChannelAdapter.triggerTestEvent("fozzie");
        assertThat(taskService.createTaskQuery().processInstanceId(kermitProcessInstance.getId()).singleResult().getTaskDefinitionKey()).isEqualTo("taskAfterBoundary");
        assertThat(taskService.createTaskQuery().processInstanceId(gonzoProcessInstance.getId()).singleResult().getTaskDefinitionKey()).isEqualTo("task");

        inboundEventChannelAdapter.triggerTestEvent("gonzo");
        assertThat(taskService.createTaskQuery().processInstanceId(kermitProcessInstance.getId()).singleResult().getTaskDefinitionKey()).isEqualTo("taskAfterBoundary");
        assertThat(taskService.createTaskQuery().processInstanceId(gonzoProcessInstance.getId()).singleResult().getTaskDefinitionKey()).isEqualTo("taskAfterBoundary");
    }
    
    @Test
    @Deployment
    public void testBoundaryEventListenerWithPayload() {
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("process");
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("task");
        
        EventSubscription eventSubscription = runtimeService.createEventSubscriptionQuery().activityId("eventBoundary").singleResult();
        assertThat(eventSubscription).isNotNull();
        assertThat(eventSubscription.getProcessInstanceId()).isEqualTo(processInstance.getId());
        assertThat(eventSubscription.getEventType()).isEqualTo("myEvent");

        inboundEventChannelAdapter.triggerTestEvent("payloadStartCustomer");
        Task afterTask = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(afterTask.getTaskDefinitionKey()).isEqualTo("taskAfterBoundary");
        
        assertThat(runtimeService.getVariables(processInstance.getId()))
            .containsOnly(
                entry("customerIdVar", "payloadStartCustomer"),
                entry("payload1", "Hello World")
            );
        
        eventSubscription = runtimeService.createEventSubscriptionQuery().activityId("eventBoundary").singleResult();
        assertThat(eventSubscription).isNull();
    }

    @Test
    @Deployment
    public void testProcessStartNoCorrelationParameter() {
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().processDefinitionKey("process").singleResult();
        assertThat(processDefinition).isNotNull();

        EventSubscription eventSubscription = runtimeService.createEventSubscriptionQuery()
            .processDefinitionId(processDefinition.getId())
            .scopeType(ScopeTypes.BPMN)
            .singleResult();
        assertThat(eventSubscription).isNotNull();
        assertThat(eventSubscription.getEventType()).isEqualTo("myEvent");

        assertThat(runtimeService.createProcessInstanceQuery().list()).hasSize(0);

        for (int i = 1; i <= 5; i++) {
            inboundEventChannelAdapter.triggerTestEvent();
            assertThat(runtimeService.createProcessInstanceQuery().list()).hasSize(i);
        }
    }
    
    @Test
    @Deployment
    public void testProcessStartSimpleCorrelationParameter() {
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().processDefinitionKey("process").singleResult();
        assertThat(processDefinition).isNotNull();

        EventSubscription eventSubscription = runtimeService.createEventSubscriptionQuery()
            .processDefinitionId(processDefinition.getId())
            .scopeType(ScopeTypes.BPMN)
            .singleResult();
        assertThat(eventSubscription).isNotNull();
        assertThat(eventSubscription.getEventType()).isEqualTo("myEvent");

        assertThat(runtimeService.createProcessInstanceQuery().list()).hasSize(0);
        
        inboundEventChannelAdapter.triggerTestEvent("anotherCustomer");
        assertThat(runtimeService.createProcessInstanceQuery().list()).hasSize(0);

        for (int i = 1; i <= 5; i++) {
            inboundEventChannelAdapter.triggerTestEvent("testCustomer");
            assertThat(runtimeService.createProcessInstanceQuery().list()).hasSize(i);
        }
    }
    
    @Test
    @Deployment
    public void testProcessStartWithPayload() {
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().processDefinitionKey("process").singleResult();
        assertThat(processDefinition).isNotNull();

        EventSubscription eventSubscription = runtimeService.createEventSubscriptionQuery()
            .processDefinitionId(processDefinition.getId())
            .scopeType(ScopeTypes.BPMN)
            .singleResult();
        assertThat(eventSubscription).isNotNull();
        assertThat(eventSubscription.getEventType()).isEqualTo("myEvent");

        assertThat(runtimeService.createProcessInstanceQuery().list()).hasSize(0);
        
        inboundEventChannelAdapter.triggerTestEvent("payloadStartCustomer");
        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processDefinitionKey("process").singleResult();
        assertThat(runtimeService.getVariables(processInstance.getId()))
            .containsOnly(
                entry("customerIdVar", "payloadStartCustomer"),
                entry("payload1", "Hello World")
            );
    }

    @Test
    @Deployment
    public void testStartOnlyOneInstance() {
        for (int i = 1; i <= 9; i++) {
            inboundEventChannelAdapter.triggerTestEvent("testCustomer");
            assertThat(runtimeService.createProcessInstanceQuery().list()).hasSize(1);
        }
        assertThat(runtimeService.createProcessInstanceQuery().singleResult().getReferenceId()).isNotNull();
        assertThat(runtimeService.createProcessInstanceQuery().singleResult().getReferenceType()).isEqualTo(ReferenceTypes.EVENT_PROCESS);

        for (int i = 1; i <= 3; i++) {
            inboundEventChannelAdapter.triggerTestEvent("anotherTestCustomer");
            assertThat(runtimeService.createProcessInstanceQuery().list()).hasSize(2);
        }
    }

    private static class TestInboundEventChannelAdapter implements InboundEventChannelAdapter {

        public InboundChannelModel inboundChannelModel;
        public EventRegistry eventRegistry;

        @Override
        public void setInboundChannelModel(InboundChannelModel inboundChannelModel) {
            this.inboundChannelModel = inboundChannelModel;
        }

        @Override
        public void setEventRegistry(EventRegistry eventRegistry) {
            this.eventRegistry = eventRegistry;
        }

        public void triggerTestEvent() {
            triggerTestEvent(null);
        }

        public void triggerTestEvent(String customerId) {
            triggerTestEvent(customerId, null);
        }

        public void triggerOrderTestEvent(String orderId) {
            triggerTestEvent(null, orderId);
        }

        public void triggerTestEvent(String customerId, String orderId) {
            ObjectMapper objectMapper = new ObjectMapper();

            ObjectNode json = objectMapper.createObjectNode();
            json.put("type", "myEvent");
            if (customerId != null) {
                json.put("customerId", customerId);
            }

            if (orderId != null) {
                json.put("orderId", orderId);
            }
            json.put("payload1", "Hello World");
            json.put("payload2", new Random().nextInt());
            try {
                eventRegistry.eventReceived(inboundChannelModel, objectMapper.writeValueAsString(json));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

    }



}
