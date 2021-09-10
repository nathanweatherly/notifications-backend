package com.redhat.cloud.notifications.db;

import com.redhat.cloud.notifications.TestLifecycleManager;
import com.redhat.cloud.notifications.models.Application;
import com.redhat.cloud.notifications.models.BehaviorGroup;
import com.redhat.cloud.notifications.models.BehaviorGroupAction;
import com.redhat.cloud.notifications.models.Bundle;
import com.redhat.cloud.notifications.models.Endpoint;
import com.redhat.cloud.notifications.models.EventType;
import com.redhat.cloud.notifications.models.EventTypeBehavior;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;
import javax.persistence.PersistenceException;
import javax.validation.ConstraintViolationException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response.Status;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.redhat.cloud.notifications.TestConstants.DEFAULT_ACCOUNT_ID;
import static com.redhat.cloud.notifications.models.EndpointType.EMAIL_SUBSCRIPTION;
import static com.redhat.cloud.notifications.models.EndpointType.WEBHOOK;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(TestLifecycleManager.class)
public class BehaviorGroupResourcesTest extends DbIsolatedTest {

    @Inject
    Mutiny.Session session;

    @Inject
    ResourceHelpers resourceHelpers;

    @Inject
    BehaviorGroupResources behaviorGroupResources;

    // This class is used to prevent reactive tests crazy nesting.
    static class ModelInstancesHolder {
        List<Bundle> bundles = new ArrayList<>();
        List<Application> apps = new ArrayList<>();
        List<EventType> ets = new ArrayList<>();
        List<BehaviorGroup> bgs = new ArrayList<>();
        List<Endpoint> eps = new ArrayList<>();
    }

    // A new instance is automatically created by JUnit before each test is executed.
    private ModelInstancesHolder model = new ModelInstancesHolder();

    @Test
    void testCreateAndUpdateAndDeleteBehaviorGroup() {
        resourceHelpers.createBundle()
                .invoke(model.bundles::add)
                // Create behavior group.
                .chain(() -> resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "displayName", model.bundles.get(0).getId())
                        .invoke(model.bgs::add)
                )
                .chain(() -> behaviorGroupResources.findByBundleId(DEFAULT_ACCOUNT_ID, model.bundles.get(0).getId())
                        .invoke(behaviorGroups -> {
                            assertEquals(1, behaviorGroups.size());
                            assertEquals(model.bgs.get(0), behaviorGroups.get(0));
                            assertEquals(model.bgs.get(0).getDisplayName(), behaviorGroups.get(0).getDisplayName());
                            assertEquals(model.bundles.get(0).getId(), behaviorGroups.get(0).getBundle().getId());
                            assertNotNull(model.bundles.get(0).getCreated());
                        })
                )
                .chain(() -> {
                    // Update behavior group.
                    String newDisplayName = "newDisplayName";
                    return updateBehaviorGroup(model.bgs.get(0).getId(), newDisplayName)
                            .invoke(updated -> {
                                assertTrue(updated);
                                session.clear(); // We need to clear the session L1 cache before checking the update result.
                            })
                            .chain(() -> behaviorGroupResources.findByBundleId(DEFAULT_ACCOUNT_ID, model.bundles.get(0).getId())
                                    .invoke(behaviorGroups -> {
                                        assertEquals(1, behaviorGroups.size());
                                        assertEquals(model.bgs.get(0).getId(), behaviorGroups.get(0).getId());
                                        assertEquals(newDisplayName, behaviorGroups.get(0).getDisplayName());
                                        assertEquals(model.bundles.get(0).getId(), behaviorGroups.get(0).getBundle().getId());
                                    })
                            );
                })
                // Delete behavior group.
                .chain(() -> resourceHelpers.deleteBehaviorGroup(model.bgs.get(0).getId())
                        .invoke(Assertions::assertTrue)
                )
                .chain(() -> behaviorGroupResources.findByBundleId(DEFAULT_ACCOUNT_ID, model.bundles.get(0).getId())
                        .invoke(behaviorGroups -> assertTrue(behaviorGroups.isEmpty()))
                )
                .await().indefinitely();
    }

    @Test
    void testCreateBehaviorGroupWithNullDisplayName() {
        createBehaviorGroupWithIllegalDisplayName(null);
    }

    @Test
    void testCreateBehaviorGroupWithEmptyDisplayName() {
        createBehaviorGroupWithIllegalDisplayName("");
    }

    @Test
    void testCreateBehaviorGroupWithBlankDisplayName() {
        createBehaviorGroupWithIllegalDisplayName(" ");
    }

    @Test
    void testCreateBehaviorGroupWithNullBundleId() {
        NotFoundException e = assertThrows(NotFoundException.class, () -> {
            resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "displayName", null)
                    .await().indefinitely();
        });
        assertEquals("bundle_id not found", e.getMessage());
    }

    @Test
    void testCreateBehaviorGroupWithUnknownBundleId() {
        NotFoundException e = assertThrows(NotFoundException.class, () -> {
            resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "displayName", UUID.randomUUID())
                    .await().indefinitely();
        });
        assertEquals("bundle_id not found", e.getMessage());
    }

    @Test
    void testfindByBundleIdOrdering() {
        resourceHelpers.createBundle()
                .invoke(model.bundles::add)
                .chain(() -> resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "displayName", model.bundles.get(0).getId())
                        .invoke(model.bgs::add)
                )
                .chain(() -> resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "displayName", model.bundles.get(0).getId())
                        .invoke(model.bgs::add)
                )
                .chain(() -> resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "displayName", model.bundles.get(0).getId())
                        .invoke(model.bgs::add)
                )
                .chain(() -> behaviorGroupResources.findByBundleId(DEFAULT_ACCOUNT_ID, model.bundles.get(0).getId())
                        .invoke(behaviorGroups -> {
                            assertEquals(3, behaviorGroups.size());
                            // Behavior groups should be sorted on descending creation date.
                            assertSame(model.bgs.get(2), behaviorGroups.get(0));
                            assertSame(model.bgs.get(1), behaviorGroups.get(1));
                            assertSame(model.bgs.get(0), behaviorGroups.get(2));
                        })
                )
                .await().indefinitely();
    }

    @Test
    void testAddAndDeleteEventTypeBehavior() {
        resourceHelpers.createBundle()
                .invoke(model.bundles::add)
                .chain(() -> resourceHelpers.createApplication(model.bundles.get(0).getId())
                    .invoke(model.apps::add)
                )
                .chain(() -> createEventType(model.apps.get(0).getId())
                        .invoke(model.ets::add)
                )
                .chain(() -> resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "Behavior group 1", model.bundles.get(0).getId())
                        .invoke(model.bgs::add)
                )
                .chain(() -> resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "Behavior group 2", model.bundles.get(0).getId())
                        .invoke(model.bgs::add)
                )
                .chain(() -> resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "Behavior group 3", model.bundles.get(0).getId())
                        .invoke(model.bgs::add)
                )
                .chain(() -> updateAndCheckEventTypeBehaviors(DEFAULT_ACCOUNT_ID, model.ets.get(0).getId(), true, model.bgs.get(0).getId()))
                .chain(() -> updateAndCheckEventTypeBehaviors(DEFAULT_ACCOUNT_ID, model.ets.get(0).getId(), true, model.bgs.get(0).getId()))
                .chain(() -> updateAndCheckEventTypeBehaviors(DEFAULT_ACCOUNT_ID, model.ets.get(0).getId(), true, model.bgs.get(0).getId(), model.bgs.get(1).getId()))
                .chain(() -> updateAndCheckEventTypeBehaviors(DEFAULT_ACCOUNT_ID, model.ets.get(0).getId(), true, model.bgs.get(1).getId()))
                .chain(() -> updateAndCheckEventTypeBehaviors(DEFAULT_ACCOUNT_ID, model.ets.get(0).getId(), true, model.bgs.get(0).getId(), model.bgs.get(1).getId(), model.bgs.get(2).getId()))
                .chain(() -> updateAndCheckEventTypeBehaviors(DEFAULT_ACCOUNT_ID, model.ets.get(0).getId(), true))
                .await().indefinitely();
    }

    @Test
    void testFindEventTypesByBehaviorGroupId() {
        resourceHelpers.createBundle()
                .invoke(model.bundles::add)
                .chain(() -> resourceHelpers.createApplication(model.bundles.get(0).getId())
                        .invoke(model.apps::add)
                )
                .chain(() -> createEventType(model.apps.get(0).getId())
                        .invoke(model.ets::add)
                )
                .chain(() -> resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "displayName", model.bundles.get(0).getId())
                        .invoke(model.bgs::add)
                )
                .chain(() -> updateAndCheckEventTypeBehaviors(DEFAULT_ACCOUNT_ID, model.ets.get(0).getId(), true, model.bgs.get(0).getId()))
                .chain(() -> resourceHelpers.findEventTypesByBehaviorGroupId(model.bgs.get(0).getId())
                        .invoke(eventTypes -> {
                            assertEquals(1, eventTypes.size());
                            assertEquals(model.ets.get(0).getId(), eventTypes.get(0).getId());
                        })
                )
                .await().indefinitely();
    }

    @Test
    void testFindBehaviorGroupsByEventTypeId() {
        resourceHelpers.createBundle()
                .invoke(model.bundles::add)
                .chain(() -> resourceHelpers.createApplication(model.bundles.get(0).getId())
                        .invoke(model.apps::add)
                )
                .chain(() -> createEventType(model.apps.get(0).getId())
                        .invoke(model.ets::add)
                )
                .chain(() -> resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "displayName", model.bundles.get(0).getId())
                        .invoke(model.bgs::add)
                )
                .chain(() -> updateAndCheckEventTypeBehaviors(DEFAULT_ACCOUNT_ID, model.ets.get(0).getId(), true, model.bgs.get(0).getId()))
                .chain(() -> resourceHelpers.findBehaviorGroupsByEventTypeId(model.ets.get(0).getId())
                        .invoke(behaviorGroups -> {
                            assertEquals(1, behaviorGroups.size());
                            assertEquals(model.bgs.get(0).getId(), behaviorGroups.get(0).getId());
                        })
                )
                .await().indefinitely();
    }

    @Test
    void testAddAndDeleteBehaviorGroupAction() {
        resourceHelpers.createBundle()
                .invoke(model.bundles::add)
                .chain(() -> resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "Behavior group 1", model.bundles.get(0).getId())
                        .invoke(model.bgs::add)
                )
                .chain(() -> resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "Behavior group 2", model.bundles.get(0).getId())
                        .invoke(model.bgs::add)
                )
                .chain(() -> resourceHelpers.createEndpoint(DEFAULT_ACCOUNT_ID, WEBHOOK)
                        .invoke(model.eps::add)
                )
                .chain(() -> resourceHelpers.createEndpoint(DEFAULT_ACCOUNT_ID, WEBHOOK)
                        .invoke(model.eps::add)
                )
                .chain(() -> resourceHelpers.createEndpoint(DEFAULT_ACCOUNT_ID, WEBHOOK)
                        .invoke(model.eps::add)
                )
                // At the beginning of the test, endpoint1 shouldn't be linked with any behavior group.
                .chain(() -> findBehaviorGroupsByEndpointId(model.eps.get(0).getId()))
                .chain(() -> updateAndCheckBehaviorGroupActions(DEFAULT_ACCOUNT_ID, model.bundles.get(0).getId(), model.bgs.get(0).getId(), OK, model.eps.get(0).getId()))
                .chain(() -> updateAndCheckBehaviorGroupActions(DEFAULT_ACCOUNT_ID, model.bundles.get(0).getId(), model.bgs.get(0).getId(), OK, model.eps.get(0).getId()))
                .chain(() -> updateAndCheckBehaviorGroupActions(DEFAULT_ACCOUNT_ID, model.bundles.get(0).getId(), model.bgs.get(0).getId(), OK, model.eps.get(0).getId(), model.eps.get(1).getId()))
                // Now, endpoint1 should be linked with behaviorGroup1.
                .chain(() -> findBehaviorGroupsByEndpointId(model.eps.get(0).getId(), model.bgs.get(0).getId()))
                .chain(() -> updateAndCheckBehaviorGroupActions(DEFAULT_ACCOUNT_ID, model.bundles.get(0).getId(), model.bgs.get(1).getId(), OK, model.eps.get(0).getId()))
                // Then, endpoint1 should be linked with both behavior groups.
                .chain(() -> findBehaviorGroupsByEndpointId(model.eps.get(0).getId(), model.bgs.get(0).getId(), model.bgs.get(1).getId()))
                .chain(() -> updateAndCheckBehaviorGroupActions(DEFAULT_ACCOUNT_ID, model.bundles.get(0).getId(), model.bgs.get(0).getId(), OK, model.eps.get(1).getId()))
                .chain(() -> updateAndCheckBehaviorGroupActions(DEFAULT_ACCOUNT_ID, model.bundles.get(0).getId(), model.bgs.get(0).getId(), OK, model.eps.get(2).getId(), model.eps.get(1).getId(), model.eps.get(0).getId()))
                .chain(() -> updateAndCheckBehaviorGroupActions(DEFAULT_ACCOUNT_ID, model.bundles.get(0).getId(), model.bgs.get(0).getId(), OK))
                // The link between endpoint1 and behaviorGroup1 was removed. Let's check it is still linked with behaviorGroup2.
                .chain(() -> findBehaviorGroupsByEndpointId(model.eps.get(0).getId(), model.bgs.get(1).getId()))
                .await().indefinitely();
    }

    @Test
    void testAddMultipleEmailSubscriptionBehaviorGroupActions() {
        resourceHelpers.createBundle()
                .invoke(model.bundles::add)
                .chain(() -> resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "displayName", model.bundles.get(0).getId())
                        .invoke(model.bgs::add)
                )
                .chain(() -> resourceHelpers.createEndpoint(DEFAULT_ACCOUNT_ID, EMAIL_SUBSCRIPTION)
                        .invoke(model.eps::add)
                )
                .chain(() -> resourceHelpers.createEndpoint(DEFAULT_ACCOUNT_ID, EMAIL_SUBSCRIPTION)
                        .invoke(model.eps::add)
                )
                .chain(() -> updateAndCheckBehaviorGroupActions(DEFAULT_ACCOUNT_ID, model.bundles.get(0).getId(), model.bgs.get(0).getId(), OK, model.eps.get(0).getId(), model.eps.get(1).getId()))
                .await().indefinitely();
    }

    @Test
    void testUpdateBehaviorGroupActionsWithWrongAccountId() {
        resourceHelpers.createBundle()
                .invoke(model.bundles::add)
                .chain(() -> resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, "displayName", model.bundles.get(0).getId())
                        .invoke(model.bgs::add)
                )
                .chain(() -> resourceHelpers.createEndpoint(DEFAULT_ACCOUNT_ID, WEBHOOK)
                        .invoke(model.eps::add)
                )
                .chain(() -> updateAndCheckBehaviorGroupActions("unknownAccountId", model.bundles.get(0).getId(), model.bgs.get(0).getId(), NOT_FOUND, model.eps.get(0).getId()))
                .await().indefinitely();
    }

    private Uni<EventType> createEventType(UUID appID) {
        EventType eventType = new EventType();
        eventType.setApplicationId(appID);
        eventType.setName("name");
        eventType.setDisplayName("displayName");
        return session.persist(eventType).call(session::flush).replaceWith(eventType);
    }

    private void createBehaviorGroupWithIllegalDisplayName(String displayName) {
        Bundle bundle = resourceHelpers.createBundle()
                .await().indefinitely();
        PersistenceException e = assertThrows(PersistenceException.class, () -> {
            resourceHelpers.createBehaviorGroup(DEFAULT_ACCOUNT_ID, displayName, bundle.getId())
                    .await().indefinitely();
        });
        assertSame(ConstraintViolationException.class, e.getCause().getCause().getClass());
        assertTrue(e.getCause().getCause().getMessage().contains("propertyPath=displayName"));
    }

    private Uni<Boolean> updateBehaviorGroup(UUID behaviorGroupId, String displayName) {
        BehaviorGroup behaviorGroup = new BehaviorGroup();
        behaviorGroup.setId(behaviorGroupId);
        behaviorGroup.setDisplayName(displayName);
        behaviorGroup.setBundleId(UUID.randomUUID()); // This should not have any effect, the bundle is not updatable.
        return resourceHelpers.updateBehaviorGroup(behaviorGroup);
    }

    private Uni<Void> updateAndCheckEventTypeBehaviors(String accountId, UUID eventTypeId, boolean expectedResult, UUID... behaviorGroupIds) {
        return behaviorGroupResources.updateEventTypeBehaviors(accountId, eventTypeId, Set.of(behaviorGroupIds))
                .invoke(updated -> {
                    // Is the update result the one we expected?
                    assertEquals(expectedResult, updated);
                })
                .onItem().transformToUni(updated -> {
                    if (expectedResult) {
                        session.clear(); // We need to clear the session L1 cache before checking the update result.
                        // If we expected a success, the event type behaviors should match in any order the given behavior groups IDs.
                        return findEventTypeBehaviorByEventTypeId(eventTypeId)
                                .invoke(behaviors -> {
                                    assertEquals(behaviorGroupIds.length, behaviors.size());
                                    for (UUID behaviorGroupId : behaviorGroupIds) {
                                        assertEquals(1L, behaviors.stream().filter(behavior -> behavior.getBehaviorGroup().getId().equals(behaviorGroupId)).count());
                                    }
                                });
                    } else {
                        return Uni.createFrom().voidItem();
                    }
                })
                .replaceWith(Uni.createFrom().voidItem());
    }

    private Uni<List<EventTypeBehavior>> findEventTypeBehaviorByEventTypeId(UUID eventTypeId) {
        String query = "FROM EventTypeBehavior WHERE eventType.id = :eventTypeId";
        return session.createQuery(query, EventTypeBehavior.class)
                .setParameter("eventTypeId", eventTypeId)
                .getResultList();
    }

    private Uni<Void> updateAndCheckBehaviorGroupActions(String accountId, UUID bundleId, UUID behaviorGroupId, Status expectedResult, UUID... endpointIds) {
        return behaviorGroupResources.updateBehaviorGroupActions(accountId, behaviorGroupId, Arrays.asList(endpointIds))
                .invoke(status -> {
                    // Is the update result the one we expected?
                    assertEquals(expectedResult, status);
                })
                .onItem().transformToUni(status -> {
                    if (expectedResult == Status.OK) {
                        session.clear(); // We need to clear the session L1 cache before checking the update result.
                        // If we expected a success, the behavior group actions should match exactly the given endpoint IDs.
                        return findBehaviorGroupActions(accountId, bundleId, behaviorGroupId)
                                .invoke(actions -> {
                                    assertEquals(endpointIds.length, actions.size());
                                    for (int i = 0; i < endpointIds.length; i++) {
                                        assertEquals(endpointIds[i], actions.get(i).getEndpoint().getId());
                                    }
                                });
                    } else {
                        return Uni.createFrom().voidItem();
                    }
                })
                .replaceWith(Uni.createFrom().voidItem());
    }

    private Uni<List<BehaviorGroupAction>> findBehaviorGroupActions(String accountId, UUID bundleId, UUID behaviorGroupId) {
        return behaviorGroupResources.findByBundleId(accountId, bundleId)
                .onItem().transform(behaviorGroups -> behaviorGroups
                        .stream().filter(behaviorGroup -> behaviorGroup.getId().equals(behaviorGroupId))
                        .findFirst().get().getActions()
                );
    }

    private Uni<Void> findBehaviorGroupsByEndpointId(UUID endpointId, UUID... expectedBehaviorGroupIds) {
        return resourceHelpers.findBehaviorGroupsByEndpointId(endpointId)
                .invoke(behaviorGroups -> {
                    List<UUID> actualBehaviorGroupIds = behaviorGroups.stream().map(BehaviorGroup::getId).collect(Collectors.toList());
                    assertEquals(expectedBehaviorGroupIds.length, actualBehaviorGroupIds.size());
                    assertTrue(actualBehaviorGroupIds.containsAll(Arrays.asList(expectedBehaviorGroupIds)));
                })
                .replaceWith(Uni.createFrom().voidItem());
    }
}
