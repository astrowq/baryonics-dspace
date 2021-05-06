/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static com.jayway.jsonpath.JsonPath.read;
import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static org.dspace.app.matcher.ResourcePolicyMatcher.matches;
import static org.dspace.app.rest.matcher.MetadataMatcher.matchMetadata;
import static org.dspace.authorize.ResourcePolicy.TYPE_SUBMISSION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.data.rest.webmvc.RestMediaTypes.TEXT_URI_LIST_VALUE;
import static org.springframework.http.MediaType.parseMediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.fileUpload;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.CharEncoding;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.dspace.app.rest.matcher.CollectionMatcher;
import org.dspace.app.rest.matcher.ItemMatcher;
import org.dspace.app.rest.matcher.MetadataMatcher;
import org.dspace.app.rest.matcher.WorkspaceItemMatcher;
import org.dspace.app.rest.model.patch.AddOperation;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.model.patch.RemoveOperation;
import org.dspace.app.rest.model.patch.ReplaceOperation;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.EntityTypeBuilder;
import org.dspace.builder.GroupBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.RelationshipBuilder;
import org.dspace.builder.RelationshipTypeBuilder;
import org.dspace.builder.WorkspaceItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.EntityType;
import org.dspace.content.Item;
import org.dspace.content.Relationship;
import org.dspace.content.RelationshipType;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.service.EntityTypeService;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.WorkspaceItemService;
import org.dspace.core.Constants;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;


/**
 * Test suite for the WorkspaceItem endpoint
 * @author Andrea Bollini (andrea.bollini at 4science.it)
 *
 */
public class WorkspaceItemRestRepositoryIT extends AbstractControllerIntegrationTest {

    @Autowired
    private ConfigurationService configurationService;
    @Autowired
    EntityTypeService entityTypeService;

    @Autowired
    private ItemService itemService;

    @Autowired
    private WorkspaceItemService workspaceItemService;

    @Autowired
    private AuthorizeService authorizeService;

    private Group embargoedGroups;
    private Group embargoedGroup1;
    private Group embargoedGroup2;
    private Group anonymousGroup;
    private EntityType publicationType;
    private EntityType journalType;
    private EntityType orgUnitType;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        context.turnOffAuthorisationSystem();

        embargoedGroups = GroupBuilder.createGroup(context)
                .withName("Embargoed Groups")
                .build();

        embargoedGroup1 = GroupBuilder.createGroup(context)
                .withName("Embargoed Group 1")
                .withParent(embargoedGroups)
                .build();

        embargoedGroup2 = GroupBuilder.createGroup(context)
                .withName("Embargoed Group 2")
                .withParent(embargoedGroups)
                .build();

        anonymousGroup = EPersonServiceFactory.getInstance().getGroupService().findByName(context, Group.ANONYMOUS);
        publicationType = entityTypeService.findByEntityType(context, "Publication");
        if (publicationType == null) {
            publicationType = EntityTypeBuilder.createEntityTypeBuilder(context, "Publication").build();
        }
        journalType = entityTypeService.findByEntityType(context, "Journal");
        if (journalType == null) {
            journalType = EntityTypeBuilder.createEntityTypeBuilder(context, "Journal").build();
        }
        orgUnitType = entityTypeService.findByEntityType(context, "OrgUnit");
        if (orgUnitType == null) {
            orgUnitType = EntityTypeBuilder.createEntityTypeBuilder(context, "OrgUnit").build();
        }
        context.restoreAuthSystemState();
    }

    @Test
    /**
     * All the workspaceitem should be returned regardless of the collection where they were created
     *
     * @throws Exception
     */
    public void findAllTest() throws Exception {
        context.setCurrentUser(admin);

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        Collection col2 = CollectionBuilder.createCollection(context, child1).withName("Collection 2").build();


        //2. Three workspace items in two different collections
        WorkspaceItem workspaceItem1 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                      .withTitle("Workspace Item 1")
                                      .withIssueDate("2017-10-17")
                                      .build();

        WorkspaceItem workspaceItem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col2)
                                      .withTitle("Workspace Item 2")
                                      .withIssueDate("2016-02-13")
                                      .build();

        WorkspaceItem workspaceItem3 = WorkspaceItemBuilder.createWorkspaceItem(context, col2)
                                      .withTitle("Workspace Item 3")
                                      .withIssueDate("2016-02-13")
                                      .build();

        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);

        getClient(token).perform(get("/api/submission/workspaceitems"))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$._embedded.workspaceitems", Matchers.containsInAnyOrder(
                        WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem1, "Workspace Item 1",
                                "2017-10-17"),
                        WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem2, "Workspace Item 2",
                                "2016-02-13"),
                        WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem3, "Workspace Item 3",
                                "2016-02-13"))))
                   .andExpect(jsonPath("$._links.self.href", Matchers.containsString("/api/submission/workspaceitems")))
                   .andExpect(jsonPath("$.page.size", is(20)))
                   .andExpect(jsonPath("$.page.totalElements", is(3)));
    }

    @Test
    public void findAllUnAuthenticatedTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();

        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();

        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        Collection col2 = CollectionBuilder.createCollection(context, child1).withName("Collection 2").build();

        WorkspaceItem workspaceItem1 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2020-11-13")
                .build();

        WorkspaceItem workspaceItem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col2)
                .withTitle("Workspace Item 2")
                .withIssueDate("2019-09-13")
                .build();

        context.restoreAuthSystemState();

        getClient().perform(get("/api/submission/workspaceitems"))
                   .andExpect(status().isUnauthorized());
    }

    @Test
    public void findAllForbiddenTest() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson eperson1 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson1@mail.com")
                .withPassword("qwerty01")
                .build();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();

        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();

        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .build();

        Collection col2 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 2")
                .build();

        context.setCurrentUser(eperson1);
        WorkspaceItem workspaceItem1 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2019-01-13")
                .build();

        WorkspaceItem workspaceItem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col2)
                .withTitle("Workspace Item 2")
                .withIssueDate("2018-09-20")
                .build();

        context.restoreAuthSystemState();

        String authTokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(authTokenAdmin).perform(get("/api/submission/workspaceitems"))
                 .andExpect(status().isOk())
                 .andExpect(jsonPath("$._embedded.workspaceitems", Matchers.containsInAnyOrder(
                   WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem1, "Workspace Item 1",
                        "2019-01-13"),
                   WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem2, "Workspace Item 2",
                        "2018-09-20"))))
                 .andExpect(jsonPath("$._links.self.href", Matchers.containsString("/api/submission/workspaceitems")))
                 .andExpect(jsonPath("$.page.size", is(20)))
                 .andExpect(jsonPath("$.page.totalElements", is(2)));

        String authToken = getAuthToken(eperson1.getEmail(), "qwerty01");
        getClient(authToken).perform(get("/api/submission/workspaceitems"))
                 .andExpect(status().isForbidden());
    }

    @Test
    /**
     * The workspaceitem endpoint must provide proper pagination
     *
     * @throws Exception
     */
    public void findAllWithPaginationTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        Collection col2 = CollectionBuilder.createCollection(context, child1).withName("Collection 2").build();


        //2. Three workspace items in two different collections
        WorkspaceItem workspaceItem1 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                      .withTitle("Workspace Item 1")
                                      .withIssueDate("2017-10-17")
                                      .build();

        WorkspaceItem workspaceItem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col2)
                                      .withTitle("Workspace Item 2")
                                      .withIssueDate("2016-02-13")
                                      .build();

        WorkspaceItem workspaceItem3 = WorkspaceItemBuilder.createWorkspaceItem(context, col2)
                                      .withTitle("Workspace Item 3")
                                      .withIssueDate("2016-02-13")
                                      .build();

        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);

        getClient(token).perform(get("/api/submission/workspaceitems").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.workspaceitems",
                        Matchers.containsInAnyOrder(
                                WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem1, "Workspace Item 1",
                                        "2017-10-17"),
                                WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem2, "Workspace Item 2",
                                        "2016-02-13"))))
                .andExpect(jsonPath("$._embedded.workspaceitems",
                        Matchers.not(Matchers.contains(WorkspaceItemMatcher
                                .matchItemWithTitleAndDateIssued(workspaceItem3, "Workspace Item 3", "2016-02-13")))));

        getClient(token).perform(get("/api/submission/workspaceitems").param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.workspaceitems",
                        Matchers.contains(WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem3,
                                "Workspace Item 3", "2016-02-13"))))
                .andExpect(jsonPath("$._embedded.workspaceitems",
                        Matchers.not(Matchers.contains(
                                WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem1, "Workspace Item 1",
                                        "2017-10-17"),
                                WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem2, "Workspace Item 2",
                                        "2016-02-13")))))
                .andExpect(jsonPath("$.page.size", is(2))).andExpect(jsonPath("$.page.totalElements", is(3)));
    }

    @Test
    /**
     * The workspaceitem resource endpoint must expose the proper structure
     *
     * @throws Exception
     */
    public void findOneTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        //2. a workspace item
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2017-10-17")
                .withAuthor("Smith, Donald").withAuthor("Doe, John")
                .withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();

        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(get("/api/submission/workspaceitems/" + witem.getID())).andExpect(status().isOk())
                .andExpect(jsonPath("$",
                        Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject(witem,
                                "Workspace Item 1", "2017-10-17", "ExtraEntry"))));
    }

    @Test
    public void findOneForbiddenTest() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson eperson1 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson1@mail.com")
                .withPassword("qwerty01")
                .build();

        EPerson eperson2 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson2@mail.com")
                .withPassword("qwerty02")
                .build();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();

        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();

        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .build();

        context.setCurrentUser(eperson1);
        WorkspaceItem witem1 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2019-09-09")
                .withAuthor("Smith, Donald")
                .withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();

        String tokenEperson1 = getAuthToken(eperson1.getEmail(), "qwerty01");
        getClient(tokenEperson1).perform(get("/api/submission/workspaceitems/" + witem1.getID()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$",
                       Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject(witem1,
                                    "Workspace Item 1", "2019-09-09", "ExtraEntry"))));

        String tokenEperson2 = getAuthToken(eperson2.getEmail(), "qwerty02");
        getClient(tokenEperson2).perform(get("/api/submission/workspaceitems/" + witem1.getID()))
                        .andExpect(status().isForbidden());
    }

    @Test
    /**
     * The workspaceitem resource endpoint must expose the proper structure
     *
     * @throws Exception
     */
    public void findOneRelsTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        //2. a workspace item
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2017-10-17")
                .withAuthor("Smith, Donald").withAuthor("Doe, John")
                .withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();

        String authToken = getAuthToken(admin.getEmail(), password);

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID() + "/collection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers
                        .is(CollectionMatcher.matchCollectionEntry(col1.getName(), col1.getID(), col1.getHandle()))
                ));

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID() + "/item"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(ItemMatcher.matchItemWithTitleAndDateIssued(witem.getItem(),
                        "Workspace Item 1", "2017-10-17"))));

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID() + "/submissionDefinition"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.is(hasJsonPath("$.id", is("traditional")))));

    }

    @Test
    /**
     * Check the response code for unexistent workspaceitem
     *
     * @throws Exception
     */
    public void findOneWrongUUIDTest() throws Exception {
        String token = getAuthToken(eperson.getEmail(), password);

        getClient(token).perform(get("/api/submission/workspaceitems/" + Integer.MAX_VALUE))
                   .andExpect(status().isNotFound());
    }

    @Test
    /**
     * Removing a workspaceitem should result in delete of all the underline resources (item and bitstreams)
     *
     * @throws Exception
     */
    public void deleteOneTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community with one collection.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity).withName("Collection 1").build();

        //2. a workspace item
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2017-10-17")
                .build();

        Item item = witem.getItem();

        //Add a bitstream to the item
        String bitstreamContent = "ThisIsSomeDummyText";
        Bitstream bitstream = null;
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            bitstream = BitstreamBuilder
                    .createBitstream(context, item, is)
                    .withName("Bitstream1")
                    .withMimeType("text/plain").build();
        }

        context.restoreAuthSystemState();

        String token = getAuthToken(eperson.getEmail(), password);

        //Delete the workspaceitem
        getClient(token).perform(delete("/api/submission/workspaceitems/" + witem.getID()))
                    .andExpect(status().is(204));

        //Trying to get deleted item should fail with 404
        getClient(token).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                   .andExpect(status().is(404));

        //Trying to get deleted workspaceitem's item should fail with 404
        getClient(token).perform(get("/api/core/items/" + item.getID()))
                   .andExpect(status().is(404));

        //Trying to get deleted workspaceitem's bitstream should fail with 404
        getClient(token).perform(get("/api/core/biststreams/" + bitstream.getID()))
                   .andExpect(status().is(404));
    }

    @Test
    public void deleteOneUnAuthenticatedTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1")
                .build();
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2019-01-01")
                .build();

        Item item = witem.getItem();

        //Add a bitstream to the item
        String bitstreamContent = "ThisIsSomeDummyText";
        Bitstream bitstream = null;
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            bitstream = BitstreamBuilder
                    .createBitstream(context, item, is)
                    .withName("Bitstream1")
                    .withMimeType("text/plain").build();
        }

        context.restoreAuthSystemState();

        getClient().perform(delete("/api/submission/workspaceitems/" + witem.getID()))
                   .andExpect(status().isUnauthorized());
    }

    @Test
    public void deleteOneForbiddenTest() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson submitter1 = EPersonBuilder.createEPerson(context)
                .withEmail("submitter1@example.com")
                .withPassword("qwerty01")
                .build();
        EPerson submitter2 = EPersonBuilder.createEPerson(context)
                .withEmail("submitter2@example.com")
                .withPassword("qwerty02")
                .build();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1")
                .withSubmitterGroup(submitter1, submitter2)
                .build();

        context.setCurrentUser(submitter1);
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2020-01-01")
                .build();

        Item item = witem.getItem();

        //Add a bitstream to the item
        String bitstreamContent = "ThisIsSomeDummyText";
        Bitstream bitstream = null;
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            bitstream = BitstreamBuilder
                    .createBitstream(context, item, is)
                    .withName("Bitstream1")
                    .withMimeType("text/plain").build();
        }

        context.setCurrentUser(submitter2);
        WorkspaceItem witem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 2")
                .withIssueDate("2020-02-02")
                .build();

        Item item2 = witem2.getItem();

        String bitstreamContent2 = "ThisIsSomeDummyText2";
        Bitstream bitstream2 = null;
        try (InputStream is2 = IOUtils.toInputStream(bitstreamContent2, CharEncoding.UTF_8)) {
            bitstream2 = BitstreamBuilder
                    .createBitstream(context, item2, is2)
                    .withName("Bitstream 2")
                    .withMimeType("text/plain").build();
        }
        context.restoreAuthSystemState();

        // submitter2 attempt to delete the workspaceitem of submitter1
        String tokenSubmitter2 = getAuthToken(submitter2.getEmail(), "qwerty02");
        getClient(tokenSubmitter2).perform(delete("/api/submission/workspaceitems/" + witem.getID()))
                                  .andExpect(status().isForbidden());

        // check that workspaceitem was not deleted
        String tokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(tokenAdmin).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                            .andExpect(status().isOk());

        // a normal user, without any special submission rights, attempt to delete the workspaceitem of submitter1
        String tokenEPerson = getAuthToken(eperson.getEmail(), password);
        getClient(tokenEPerson).perform(delete("/api/submission/workspaceitems/" + witem.getID()))
                                  .andExpect(status().isForbidden());

        // check that workspaceitem was not deleted
        getClient(tokenAdmin).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                            .andExpect(status().isOk());
    }

    @Test
    /**
     * Create three workspaceitem with two different submitter and verify that the findBySubmitter return the proper
     * list of workspaceitem for each submitter also paginating
     *
     * @throws Exception
     */
    public void findBySubmitterTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        Collection col2 = CollectionBuilder.createCollection(context, child1).withName("Collection 2").build();

        //2. create two users to use as submitters
        EPerson submitter1 = EPersonBuilder.createEPerson(context)
                .withEmail("submitter1@example.com")
                .withPassword("qwerty01")
                .build();
        EPerson submitter2 = EPersonBuilder.createEPerson(context)
                .withEmail("submitter2@example.com")
                .withPassword("qwerty02")
                .build();

        // create two workspaceitems with the first submitter
        context.setCurrentUser(submitter1);


        //3. Two workspace items in two different collections
        WorkspaceItem workspaceItem1 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                      .withTitle("Workspace Item 1")
                                      .withIssueDate("2017-10-17")
                                      .build();

        WorkspaceItem workspaceItem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col2)
                                      .withTitle("Workspace Item 2")
                                      .withIssueDate("2016-02-13")
                                      .build();

        //4. A workspaceitem for the second submitter
        context.setCurrentUser(submitter2);
        WorkspaceItem workspaceItem3 = WorkspaceItemBuilder.createWorkspaceItem(context, col2)
                                      .withTitle("Workspace Item 3")
                                      .withIssueDate("2016-02-13")
                                      .build();

        context.restoreAuthSystemState();
        // the first submitter has two workspace
        String tokenSubmitter1 = getAuthToken(submitter1.getEmail(), "qwerty01");
        getClient(tokenSubmitter1).perform(get("/api/submission/workspaceitems/search/findBySubmitter")
                .param("size", "20")
                .param("uuid", submitter1.getID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._embedded.workspaceitems",
                    Matchers.containsInAnyOrder(
                            WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem1, "Workspace Item 1",
                                    "2017-10-17"),
                            WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem2, "Workspace Item 2",
                                    "2016-02-13"))))
            .andExpect(jsonPath("$._embedded.workspaceitems",
                    Matchers.not(Matchers.contains(WorkspaceItemMatcher
                            .matchItemWithTitleAndDateIssued(workspaceItem3, "Workspace Item 3", "2016-02-13")))))
            .andExpect(jsonPath("$.page.size", is(20)))
            .andExpect(jsonPath("$.page.totalElements", is(2)));

        // the first submitter has two workspace so if we paginate with a 1-size windows the page 1 will contains the
        // second workspace
        getClient(tokenSubmitter1).perform(get("/api/submission/workspaceitems/search/findBySubmitter")
                .param("size", "1")
                .param("page", "1")
                .param("uuid", submitter1.getID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._embedded.workspaceitems",
                    Matchers.contains(WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem2,
                            "Workspace Item 2", "2016-02-13"))))
            .andExpect(jsonPath("$._embedded.workspaceitems",
                    Matchers.not(Matchers.contains(
                            WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem1, "Workspace Item 1",
                                    "2017-10-17"),
                            WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem3, "Workspace Item 3",
                                    "2016-02-13")))))
            .andExpect(jsonPath("$.page.size", is(1)))
            .andExpect(jsonPath("$.page.totalElements", is(2)));

        // the second submitter has a single workspace
        String tokenSubmitter2 = getAuthToken(submitter2.getEmail(), "qwerty02");
        getClient(tokenSubmitter2).perform(get("/api/submission/workspaceitems/search/findBySubmitter")
                .param("size", "20")
                .param("uuid", submitter2.getID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._embedded.workspaceitems",
                    Matchers.contains(
                            WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem3, "Workspace Item 3",
                                    "2016-02-13"))))
            .andExpect(jsonPath("$.page.size", is(20)))
            .andExpect(jsonPath("$.page.totalElements", is(1)));

        // also the admin should be able to retrieve the submission of an another user
        String tokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(tokenAdmin).perform(get("/api/submission/workspaceitems/search/findBySubmitter")
                             .param("uuid", submitter1.getID().toString()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$._embedded.workspaceitems", Matchers.containsInAnyOrder(
                                        WorkspaceItemMatcher.matchItemWithTitleAndDateIssued
                                                  (workspaceItem1, "Workspace Item 1", "2017-10-17"),
                                        WorkspaceItemMatcher.matchItemWithTitleAndDateIssued
                                                  (workspaceItem2, "Workspace Item 2", "2016-02-13"))))
                             .andExpect(jsonPath("$.page.totalElements", is(2)));
    }

    @Test
    public void findBySubmitterForbiddenTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();

        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();

        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .build();

        EPerson submitter1 = EPersonBuilder.createEPerson(context)
                .withEmail("submitter1@example.com")
                .withPassword("qwerty01")
                .build();

        EPerson submitter2 = EPersonBuilder.createEPerson(context)
                .withEmail("submitter2@example.com")
                .withPassword("qwerty02")
                .build();

        context.setCurrentUser(submitter1);

        WorkspaceItem workspaceItem1 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                      .withTitle("Workspace Item 1")
                                      .withIssueDate("2019-10-17")
                                      .build();

        WorkspaceItem workspaceItem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                      .withTitle("Workspace Item 2")
                                      .withIssueDate("2018-02-13")
                                      .build();

        context.restoreAuthSystemState();
        String tokenSubmitter1 = getAuthToken(submitter1.getEmail(), "qwerty01");
        getClient(tokenSubmitter1).perform(get("/api/submission/workspaceitems/search/findBySubmitter")
                .param("uuid", submitter1.getID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.workspaceitems", Matchers.containsInAnyOrder(
                            WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem1, "Workspace Item 1",
                                    "2019-10-17"),
                            WorkspaceItemMatcher.matchItemWithTitleAndDateIssued(workspaceItem2, "Workspace Item 2",
                                    "2018-02-13"))));

        String tokenSubmitter2 = getAuthToken(submitter2.getEmail(), "qwerty02");
        getClient(tokenSubmitter2).perform(get("/api/submission/workspaceitems/search/findBySubmitter")
                .param("uuid", submitter1.getID().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    /**
     * Test the creation of workspaceitem POSTing to the resource collection endpoint. It should respect the collection
     * param if present or use a default if it is not used
     *
     * @throws Exception
     */
    public void createEmptyWorkspateItemTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .withEntityType("Publication")
                                           .withSubmitterGroup(eperson)
                                           .build();
        Collection col2 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 2")
                                           .withSubmitterGroup(eperson)
                                           .build();
        context.restoreAuthSystemState();

        AtomicReference<Integer> idRef1 = new AtomicReference<>();
        AtomicReference<Integer> idRef2 = new AtomicReference<>();
        AtomicReference<Integer> idRef3 = new AtomicReference<>();
        try {

        String authToken = getAuthToken(eperson.getEmail(), password);

        // create a workspaceitem explicitly in the col1
        getClient(authToken).perform(post("/api/submission/workspaceitems")
                    .param("owningCollection", col1.getID().toString())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._embedded.collection.id", is(col1.getID().toString())))
                .andDo(result -> idRef1.set(read(result.getResponse().getContentAsString(), "$.id")));

        // create a workspaceitem explicitly in the col2
        getClient(authToken).perform(post("/api/submission/workspaceitems")
                    .param("owningCollection", col2.getID().toString())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._embedded.collection.id", is(col2.getID().toString())))
                .andDo(result -> idRef2.set(read(result.getResponse().getContentAsString(), "$.id")));

        // create a workspaceitem without an explicit collection, this will go in the first valid collection for the
        // user: the col1
        getClient(authToken).perform(post("/api/submission/workspaceitems")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._embedded.collection.id", is(col1.getID().toString())))
                .andExpect(jsonPath("$", WorkspaceItemMatcher.matchFullEmbeds()))
                .andDo(result -> idRef3.set(read(result.getResponse().getContentAsString(), "$.id")));


        } finally {
            WorkspaceItemBuilder.deleteWorkspaceItem(idRef1.get());
            WorkspaceItemBuilder.deleteWorkspaceItem(idRef2.get());
            WorkspaceItemBuilder.deleteWorkspaceItem(idRef3.get());
        }
    }

    @Test
    /**
     * Test the creation of workspaceitems POSTing to the resource collection endpoint a bibtex file
     *
     * @throws Exception
     */
    public void createSingleWorkspaceItemFromBibtexFileWithOneEntryTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .withSubmitterGroup(eperson)
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .build();
        Collection col2 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 2")
                                           .withSubmitterGroup(eperson)
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .build();

        InputStream bibtex = getClass().getResourceAsStream("bibtex-test.bib");
        final MockMultipartFile bibtexFile = new MockMultipartFile("file", "/local/path/bibtex-test.bib",
            "application/x-bibtex", bibtex);

        context.restoreAuthSystemState();

        AtomicReference<List<Integer>> idRef = new AtomicReference<>();
        String authToken = getAuthToken(eperson.getEmail(), password);
        try {
            // create a workspaceitem from a single bibliographic entry file explicitly in the default collection (col1)
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                    .file(bibtexFile))
                // create should return 200, 201 (created) is better for single resource
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][0].value",
                        is("My Article")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[0]._embedded.collection.id", is(col1.getID().toString())))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.source'][0].value",
                        is("/local/path/bibtex-test.bib")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.title'][0].value",
                        is("bibtex-test.bib")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[*]._embedded.upload").doesNotExist())
                .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                    "$._embedded.workspaceitems[*].id")));
        } finally {
            if (idRef != null && idRef.get() != null) {
                for (int i : idRef.get()) {
                    WorkspaceItemBuilder.deleteWorkspaceItem(i);
                }
            }
        }

        // create a workspaceitem from a single bibliographic entry file explicitly in the col2
        try {
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                    .file(bibtexFile)
                    .param("owningCollection", col2.getID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][0].value",
                        is("My Article")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[0]._embedded.collection.id", is(col2.getID().toString())))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.source'][0].value",
                        is("/local/path/bibtex-test.bib")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload"
                     + ".files[0].metadata['dc.title'][0].value",
                        is("bibtex-test.bib")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[*]._embedded.upload").doesNotExist())
                .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                        "$._embedded.workspaceitems[*].id")));
        } finally {
            if (idRef != null && idRef.get() != null) {
                for (int i : idRef.get()) {
                    WorkspaceItemBuilder.deleteWorkspaceItem(i);
                }
            }
        }
        bibtex.close();
    }

    @Test
    /**
     * Test the creation of workspaceitems POSTing to the resource collection endpoint a csv file
     *
     * @throws Exception
     */
    public void createSingleWorkspaceItemFromCSVWithOneEntryTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();
        Collection col2 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 2")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();

        InputStream csv = getClass().getResourceAsStream("csv-test.csv");
        final MockMultipartFile csvFile = new MockMultipartFile("file", "/local/path/csv-test.csv",
            "text/csv", csv);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);
        // create workspaceitems in the default collection (col1)
        AtomicReference<List<Integer>> idRef = new AtomicReference<>();
        try {
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                    .file(csvFile))
                // create should return 200, 201 (created) is better for single resource
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][0].value",
                        is("My Article")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                        + "['dc.contributor.author'][0].value",
                        is("Nobody")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                        + "['dc.date.issued'][0].value",
                        is("2006")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                        + "['dc.identifier.issn'][0].value",
                        is("Mock ISSN")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.type'][0].value",
                        is("Mock subtype")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[0]._embedded.collection.id", is(col1.getID().toString())))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.source'][0].value",
                        is("/local/path/csv-test.csv")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.title'][0].value",
                        is("csv-test.csv")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[*]._embedded.upload").doesNotExist())
                .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                        "$._embedded.workspaceitems[*].id")));
        } finally {
            if (idRef != null && idRef.get() != null) {
                for (int i : idRef.get()) {
                    WorkspaceItemBuilder.deleteWorkspaceItem(i);
                }
            }
        }

        // create workspaceitems explicitly in the col2
        try {
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                    .file(csvFile)
                    .param("owningCollection", col2.getID().toString()))
                    .andExpect(status().isOk())
                 .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                     + "['dc.title'][0].value",
                     is("My Article")))
                 .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                     + "['dc.contributor.author'][0].value",
                     is("Nobody")))
                 .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                     + "['dc.date.issued'][0].value",
                     is("2006")))
                 .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                     + "['dc.identifier.issn'][0].value",
                     is("Mock ISSN")))
                 .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.type'][0].value",
                     is("Mock subtype")))
                 .andExpect(
                        jsonPath("$._embedded.workspaceitems[0]._embedded.collection.id", is(col2.getID().toString())))
                 .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.source'][0].value",
                        is("/local/path/csv-test.csv")))
                 .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload"
                     + ".files[0].metadata['dc.title'][0].value",
                        is("csv-test.csv")))
                 .andExpect(
                     jsonPath("$._embedded.workspaceitems[*]._embedded.upload").doesNotExist())
                 .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                         "$._embedded.workspaceitems[*].id")));
        } finally {
            if (idRef != null && idRef.get() != null) {
                for (int i : idRef.get()) {
                    WorkspaceItemBuilder.deleteWorkspaceItem(i);
                }
            }
        }
        csv.close();
    }

    @Test
    /**
     * Test the creation of workspaceitems POSTing to the resource collection endpoint a csv file
     * with some missing data
     *
     * @throws Exception
     */
    public void createSingleWorkspaceItemFromCSVWithOneEntryAndMissingDataTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();
        Collection col2 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 2")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();

        InputStream csv = getClass().getResourceAsStream("csv-missing-field-test.csv");
        final MockMultipartFile csvFile = new MockMultipartFile("file", "/local/path/csv-missing-field-test.csv",
            "text/csv", csv);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);
        AtomicReference<List<Integer>> idRef = new AtomicReference<>();
        // create workspaceitems in the default collection (col1)

        try {
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                .file(csvFile))
            // create should return 200, 201 (created) is better for single resource
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][0].value",
                    is("My Article")))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                + "['dc.contributor.author'][0].value",
                    is("Nobody")))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                    + "['dc.contributor.author'][1].value",
                        is("Try escape, in item")))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                    + "['dc.date.issued'][0].value").isEmpty())
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                    + "['dc.identifier.issn'][0].value",
                    is("Mock ISSN")))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.type'][0].value"
                    ).doesNotExist())
            .andExpect(
                    jsonPath("$._embedded.workspaceitems[0]._embedded.collection.id", is(col1.getID().toString())))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                 + ".metadata['dc.source'][0].value",
                    is("/local/path/csv-missing-field-test.csv")))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                 + ".metadata['dc.title'][0].value",
                    is("csv-missing-field-test.csv")))
            .andExpect(
                    jsonPath("$._embedded.workspaceitems[*]._embedded.upload").doesNotExist())
            .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                    "$._embedded.workspaceitems[*].id")));
        } finally {
            if (idRef != null && idRef.get() != null) {
                for (int i : idRef.get()) {
                    WorkspaceItemBuilder.deleteWorkspaceItem(i);
                }
            }
        }
        csv.close();
    }

    @Test
    /**
     * Test the creation of workspaceitems POSTing to the resource collection endpoint a tsv file
     *
     * @throws Exception
     */
    public void createSingleWorkspaceItemFromTSVWithOneEntryTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();
        Collection col2 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 2")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();

        InputStream tsv = getClass().getResourceAsStream("tsv-test.tsv");
        final MockMultipartFile tsvFile = new MockMultipartFile("file", "/local/path/tsv-test.tsv",
            "text/tsv", tsv);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);
        AtomicReference<List<Integer>> idRef = new AtomicReference<>();

        // create workspaceitems in the default collection (col1)
        try {
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                    .file(tsvFile))
                // create should return 200, 201 (created) is better for single resource
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][0].value",
                        is("My Article")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                        + "['dc.contributor.author'][0].value",
                        is("Nobody")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                        + "['dc.date.issued'][0].value",
                        is("2006")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                        + "['dc.identifier.issn'][0].value",
                        is("Mock ISSN")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.type'][0].value",
                        is("Mock subtype")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[0]._embedded.collection.id", is(col1.getID().toString())))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.source'][0].value",
                        is("/local/path/tsv-test.tsv")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.title'][0].value",
                        is("tsv-test.tsv")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[*]._embedded.upload").doesNotExist())
                .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                        "$._embedded.workspaceitems[*].id")));
        } finally {
            if (idRef != null && idRef.get() != null) {
                for (int i : idRef.get()) {
                    WorkspaceItemBuilder.deleteWorkspaceItem(i);
                }
            }
        }
        tsv.close();
    }

    @Test
    /**
     * Test the creation of workspaceitems POSTing to the resource collection endpoint a ris file
     *
     * @throws Exception
     */
    public void createSingleWorkspaceItemFromRISWithOneEntryTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();
        Collection col2 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 2")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();

        InputStream ris = getClass().getResourceAsStream("ris-test.ris");
        final MockMultipartFile tsvFile = new MockMultipartFile("file", "/local/path/ris-test.ris",
            "text/ris", ris);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);
        AtomicReference<List<Integer>> idRef = new AtomicReference<>();

        // create workspaceitems in the default collection (col1)
        try {
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                    .file(tsvFile))
                // create should return 200, 201 (created) is better for single resource
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][0].value",
                        is("Challenge–Response Identification")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][1].value",
                        is("Challenge–Response Identification second title")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                        + "['dc.contributor.author'][0].value",
                        is("Just, Mike")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                        + "['dc.date.issued'][0].value",
                        is("2005")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                        + "['dc.identifier.issn'][0].value",
                        is("978-0-387-23483-0")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.type'][0].value",
                        is("Mock subtype")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[0]._embedded.collection.id", is(col1.getID().toString())))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.source'][0].value",
                        is("/local/path/ris-test.ris")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.title'][0].value",
                        is("ris-test.ris")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[*]._embedded.upload").doesNotExist())
                .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                                "$._embedded.workspaceitems[*].id")));
        } finally {
            if (idRef != null && idRef.get() != null) {
                for (int i : idRef.get()) {
                    WorkspaceItemBuilder.deleteWorkspaceItem(i);
                }
            }
        }
        ris.close();
    }

    @Test
    /**
     * Test the creation of workspaceitems POSTing to the resource collection endpoint an endnote file
     *
     * @throws Exception
     */
    public void createSingleWorkspaceItemFromEndnoteWithOneEntryTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();
        Collection col2 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 2")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();

        InputStream endnote = getClass().getResourceAsStream("endnote-test.enw");
        final MockMultipartFile endnoteFile = new MockMultipartFile("file", "/local/path/endnote-test.enw",
            "text/endnote", endnote);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);
        AtomicReference<List<Integer>> idRef = new AtomicReference<>();
        // create workspaceitems in the default collection (col1)
        try {
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                    .file(endnoteFile))
                // create should return 200, 201 (created) is better for single resource
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][0].value",
                        is("My Title")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                        + "['dc.contributor.author'][0].value",
                        is("Author 1")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                        + "['dc.contributor.author'][1].value",
                        is("Author 2")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                        + "['dc.date.issued'][0].value",
                        is("2005")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpagetwo"
                        + "['dc.description.abstract'][0].value",
                        is("This is my abstract")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[0]._embedded.collection.id", is(col1.getID().toString())))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.source'][0].value",
                        is("/local/path/endnote-test.enw")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.title'][0].value",
                        is("endnote-test.enw")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[*]._embedded.upload").doesNotExist())
                .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                        "$._embedded.workspaceitems[*].id")));
        } finally {
            if (idRef != null && idRef.get() != null) {
                for (int i : idRef.get()) {
                     WorkspaceItemBuilder.deleteWorkspaceItem(i);
                }
            }
        }
        endnote.close();
    }


    @Test
    /**
     * Test the creation of workspaceitems POSTing to the resource collection endpoint a csv file
     * with some missing data and inner tab in field (those have to be read as list)
     *
     * @throws Exception
     */
    public void createSingleWorkspaceItemFromTSVWithOneEntryAndMissingDataTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();
        Collection col2 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 2")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();

        InputStream tsv = getClass().getResourceAsStream("tsv-missing-field-test.tsv");
        final MockMultipartFile csvFile = new MockMultipartFile("file", "/local/path/tsv-missing-field-test.tsv",
            "text/tsv", tsv);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);
        AtomicReference<List<Integer>> idRef = new AtomicReference<>();

        // create workspaceitems in the default collection (col1)
        try {
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                .file(csvFile))
            // create should return 200, 201 (created) is better for single resource
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][0].value",
                    is("My Article")))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                + "['dc.contributor.author'][0].value",
                    is("Nobody")))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                    + "['dc.contributor.author'][1].value",
                        is("Try escape \t\t\tin \t\titem")))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                    + "['dc.date.issued'][0].value").isEmpty())
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                    + "['dc.identifier.issn'][0].value",
                    is("Mock ISSN")))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.type'][0].value"
                    ).doesNotExist())
            .andExpect(
                    jsonPath("$._embedded.workspaceitems[0]._embedded.collection.id", is(col1.getID().toString())))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                 + ".metadata['dc.source'][0].value",
                    is("/local/path/tsv-missing-field-test.tsv")))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                 + ".metadata['dc.title'][0].value",
                    is("tsv-missing-field-test.tsv")))
            .andExpect(
                    jsonPath("$._embedded.workspaceitems[*]._embedded.upload").doesNotExist())
            .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                    "$._embedded.workspaceitems[*].id")));
            } finally {
                if (idRef != null && idRef.get() != null) {
                    for (int i : idRef.get()) {
                        WorkspaceItemBuilder.deleteWorkspaceItem(i);
                    }
                }
            }
            tsv.close();
    }

    @Test
    /**
     * Test the creation of workspaceitems POSTing to the resource collection endpoint a
     * bibtex and pubmed files
     *
     * @throws Exception
     */
    public void createSingleWorkspaceItemFromMultipleFilesWithOneEntryTest() throws Exception {
        context.turnOffAuthorisationSystem();
        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();
        Collection col2 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 2")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();

        InputStream bibtex = getClass().getResourceAsStream("bibtex-test.bib");
        final MockMultipartFile bibtexFile = new MockMultipartFile("file", "/local/path/bibtex-test.bib",
            "application/x-bibtex", bibtex);
        InputStream xmlIS = getClass().getResourceAsStream("pubmed-test.xml");
        final MockMultipartFile pubmedFile = new MockMultipartFile("file", "/local/path/pubmed-test.xml",
            "application/xml", xmlIS);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);
        AtomicReference<List<Integer>> idRef = new AtomicReference<>();

        // create a workspaceitem from a single bibliographic entry file explicitly in the default collection (col1)
        try {
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                    .file(bibtexFile).file(pubmedFile))
                // create should return 200, 201 (created) is better for single resource
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][0].value",
                        is("My Article")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[0]._embedded.collection.id", is(col1.getID().toString())))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.source'][0].value",
                        is("/local/path/bibtex-test.bib")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.title'][0].value",
                        is("bibtex-test.bib")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][1].value")
                    .doesNotExist())
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[1]"
                        + ".metadata['dc.source'][0].value",
                            is("/local/path/pubmed-test.xml")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[1]"
                        + ".metadata['dc.title'][0].value",
                            is("pubmed-test.xml")))
                .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                        "$._embedded.workspaceitems[*].id")));
        } finally {
            if (idRef != null && idRef.get() != null) {
                for (int i : idRef.get()) {
                    WorkspaceItemBuilder.deleteWorkspaceItem(i);
                }
            }
        }

        // create a workspaceitem from a single bibliographic entry file explicitly in the col2
        try {
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                    .file(bibtexFile).file(pubmedFile)
                    .param("owningCollection", col2.getID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][0].value",
                        is("My Article")))
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[0]._embedded.collection.id", is(col2.getID().toString())))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                     + ".metadata['dc.source'][0].value",
                        is("/local/path/bibtex-test.bib")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload"
                     + ".files[0].metadata['dc.title'][0].value",
                        is("bibtex-test.bib")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][1].value")
                        .doesNotExist())
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[1]"
                     + ".metadata['dc.source'][0].value",
                         is("/local/path/pubmed-test.xml")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[1]"
                     + ".metadata['dc.title'][0].value",
                         is("pubmed-test.xml")))
                .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                        "$._embedded.workspaceitems[*].id")));
        } finally {
            if (idRef != null && idRef.get() != null) {
                for (int i : idRef.get()) {
                    WorkspaceItemBuilder.deleteWorkspaceItem(i);
                }
            }
        }
        bibtex.close();
        xmlIS.close();
    }

    @Test
    /**
     * Test the creation of workspaceitems POSTing to the resource collection endpoint a bibtex file
     * contains more than one entry.
     *
     * @throws Exception
     */
    public void createSingleWorkspaceItemsFromSingleFileWithMultipleEntriesTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .withSubmitterGroup(eperson)
                                           .build();
        Collection col2 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 2")
                                           .withSubmitterGroup(eperson)
                                           .build();

        InputStream bibtex = getClass().getResourceAsStream("bibtex-test-3-entries.bib");
        final MockMultipartFile bibtexFile = new MockMultipartFile("file", "bibtex-test-3-entries.bib",
            "application/x-bibtex",
                bibtex);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);
        // create a workspaceitem from a single bibliographic entry file explicitly in the default collection (col1)
        getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                    .file(bibtexFile))
                  // create should return return a 422 because we don't allow/support bibliographic files
                 // that have multiple metadata records
                .andExpect(status().is(422));
        bibtex.close();
    }

    @Test
    /**
     * Test the creation of workspaceitems POSTing to the resource collection endpoint a pubmed XML
     * file.
     *
     * @throws Exception
     */
    public void createPubmedWorkspaceItemFromFileTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();
        Collection col2 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 2")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withSubmitterGroup(eperson)
                                           .build();
        InputStream xmlIS = getClass().getResourceAsStream("pubmed-test.xml");
        final MockMultipartFile pubmedFile = new MockMultipartFile("file", "/local/path/pubmed-test.xml",
            "application/xml", xmlIS);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);
        AtomicReference<List<Integer>> idRef = new AtomicReference<>();

        // create a workspaceitem from a single bibliographic entry file explicitly in the default collection (col1)
        try {
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                    .file(pubmedFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][0].value",
                        is("Multistep microreactions with proteins using electrocapture technology.")))
                .andExpect(
                        jsonPath(
                        "$._embedded.workspaceitems[0].sections.traditionalpageone['dc.identifier.pmid'][0].value",
                        is("15117179")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                        + "['dc.contributor.author'][0].value",
                        is("Astorga-Wells, Juan")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                    + ".metadata['dc.source'][0].value",
                        is("/local/path/pubmed-test.xml")))
                .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0]"
                    + ".metadata['dc.title'][0].value",
                        is("pubmed-test.xml")))
                .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                        "$._embedded.workspaceitems[*].id")));
        } finally {
            if (idRef != null && idRef.get() != null) {
                for (int i : idRef.get()) {
                    WorkspaceItemBuilder.deleteWorkspaceItem(i);
                }
            }
        }


        // create a workspaceitem from a single bibliographic entry file explicitly in the col2
        try {
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                    .file(pubmedFile)
                    .param("owningCollection", col2.getID().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][0].value",
                is("Multistep microreactions with proteins using electrocapture technology.")))
            .andExpect(
                jsonPath(
                "$._embedded.workspaceitems[0].sections.traditionalpageone['dc.identifier.pmid'][0].value",
                is("15117179")))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.traditionalpageone"
                + "['dc.contributor.author'][0].value",
                is("Astorga-Wells, Juan")))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0].metadata['dc.source'][0].value",
                    is("/local/path/pubmed-test.xml")))
            .andExpect(jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0].metadata['dc.title'][0].value",
                    is("pubmed-test.xml")))
            .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                    "$._embedded.workspaceitems[*].id")));
        } finally {
            if (idRef != null && idRef.get() != null) {
                for (int i : idRef.get()) {
                    WorkspaceItemBuilder.deleteWorkspaceItem(i);
                }
            }
        }
        xmlIS.close();
    }

    @Test
    /**
     * Test the creation of a workspaceitem POSTing to the resource collection endpoint a PDF file. As a single item
     * will be created we expect to have the pdf file stored as a bitstream
     *
     * @throws Exception
     */
    public void createWorkspaceItemFromPDFFileTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .withEntityType("Publication")
                .withSubmissionDefinition("traditional")
                .withSubmitterGroup(eperson)
                .build();

        String authToken = getAuthToken(eperson.getEmail(), password);

        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        MockMultipartFile pdfFile = new MockMultipartFile("file", "/local/path/myfile.pdf", "application/pdf", pdf);

        context.restoreAuthSystemState();
        AtomicReference<List<Integer>> idRef = new AtomicReference<>();
        try {
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems")
                    .file(pdfFile))
                // bulk create should return 200, 201 (created) is better for single resource
                .andExpect(status().isOk())
                // testing grobid extraction
                .andExpect(jsonPath(
                      "$._embedded.workspaceitems[0].sections.traditionalpageone['dc.title'][0].value",
                is("This is a simple test file")))
                .andExpect(jsonPath(
                      "$._embedded.workspaceitems[0].sections.traditionalpageone['dc.contributor.author'][0].value",
                is("Bollini, Andrea")))
                .andExpect(jsonPath(
                      "$._embedded.workspaceitems[0].sections.traditionalpageone['dc.date.issued'][0].value",
                is("2018")))
                .andExpect(jsonPath(
                      "$._embedded.workspaceitems[0].sections.traditionalpagetwo['dc.description.abstract'][0].value",
                is("This is the abstract of our PDF file")))
                // we can just check that the pdf is stored in the item
                .andExpect(
                        jsonPath("$._embedded.workspaceitems[0].sections.upload.files[0].metadata['dc.title'][0].value",
                                is("myfile.pdf")))
                .andExpect(jsonPath(
                        "$._embedded.workspaceitems[0].sections.upload.files[0].metadata['dc.source'][0].value",
                        is("/local/path/myfile.pdf")))
            .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                    "$._embedded.workspaceitems[*].id")));
        } finally {
            if (idRef != null && idRef.get() != null) {
                for (int i : idRef.get()) {
                    WorkspaceItemBuilder.deleteWorkspaceItem(i);
                }
            }
        }
        pdf.close();
    }

    @Test
    public void createSingleWorkspaceItemWithTemplate() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson user = EPersonBuilder.createEPerson(context)
                                     .withNameInMetadata("Andrea", "Lenci")
                                     .withEmail("andrea.lenci@test.com")
                                     .withPassword(password)
                                     .build();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .withEntityType("Publication")
                                           .withSubmissionDefinition("traditional")
                                           .withTemplateItem()
                                           .withSubmitterGroup(user)
                                           .build();

        Group group = GroupBuilder.createGroup(context)
                                  .withName("Tets-Group")
                                  .build();

        itemService.addMetadata(context, col1.getTemplateItem(), "dc", "title", null, null, "SimpleTitle");
        itemService.addMetadata(context, col1.getTemplateItem(), "dc", "date", "issued", null, "###DATE.yyyy-MM-dd###");
        itemService.addMetadata(context, col1.getTemplateItem(),
                                "cris", "policy", "eperson", null, "###CURRENTUSER###");
        itemService.addMetadata(context, col1.getTemplateItem(),
                                "cris", "policy", "group", null, "###GROUP.Tets-Group###");

        String authToken = getAuthToken(user.getEmail(), password);

        context.restoreAuthSystemState();

        final String today = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(LocalDate.now());

        getClient(authToken).perform(post("/api/submission/workspaceitems")
                            .param("owningCollection", col1.getID().toString())
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._embedded.item.metadata['dc.title'][0].value", is("SimpleTitle")))
                .andExpect(jsonPath("$._embedded.item.metadata['dc.date.issued'][0].value", is(today)))
                .andExpect(jsonPath("$._embedded.item.metadata['cris.policy.eperson'][0].value", is(user.getEmail())))
                .andExpect(jsonPath("$._embedded.item.metadata['cris.policy.eperson'][0].authority",
                                 is(user.getID().toString())))
                .andExpect(jsonPath("$._embedded.item.metadata['cris.policy.group'][0].value", is(group.getName())))
                .andExpect(jsonPath("$._embedded.item.metadata['cris.policy.group'][0].authority",
                                 is(group.getID().toString())))
                .andExpect(jsonPath("$._embedded.collection.id", is(col1.getID().toString())));
    }

    @Test
    /**
     * Test the exposition of validation error for missing required metadata both at the creation time than on existent
     * workspaceitems
     *
     * @throws Exception
     */
    public void validationErrorsRequiredMetadataTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .withSubmitterGroup(eperson)
                .build();

        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem workspaceItem1 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2017-10-17")
                .build();

        WorkspaceItem workspaceItem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 2")
                .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        AtomicReference<Integer> idRef = new AtomicReference<>();
        try {

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + workspaceItem1.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist())
        ;

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + workspaceItem2.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[?(@.message=='error.validation.required')]",
                        Matchers.contains(
                                hasJsonPath("$.paths", Matchers.contains(
                                        hasJsonPath("$", Matchers.is("/sections/traditionalpageone/dc.date.issued"))
                                )))))
        ;

        // create an empty workspaceitem explicitly in the col1, check validation on creation
        getClient(authToken).perform(post("/api/submission/workspaceitems")
                    .param("owningCollection", col1.getID().toString())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                // title and dateissued are required in the first panel
                // the json path with a @ selector always return an array
                .andExpect(jsonPath("$.errors[?(@.message=='error.validation.required')]",
                        Matchers.contains(
                                hasJsonPath("$.paths", Matchers.containsInAnyOrder(
                                        hasJsonPath("$", Matchers.is("/sections/traditionalpageone/dc.title")),
                                        hasJsonPath("$", Matchers.is("/sections/traditionalpageone/dc.date.issued"))
                                )))))
                .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(), "$.id")));
        } finally {
            WorkspaceItemBuilder.deleteWorkspaceItem(idRef.get());
        }

    }

    /**
     * Test a global submission validation error
     *
     * @throws Exception
     */
    @Test
    public void globalValidationErrorsTest() throws Exception {
        context.turnOffAuthorisationSystem();

        // ** GIVEN **
        // 1. A community-collection structure with one parent community with
        // sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
            .withName("Parent Community")
            .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
            .withName("Sub Community")
            .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
            .withName("Collection 1")
            .withSubmitterGroup(eperson)
            .build();

        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem workspaceItem1 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
            .withTitle("Test publication with mandatory DOI 1")
            .withIssueDate("2017-10-17")
            .withDoiIdentifier("10.1000/182")
            .build();

        WorkspaceItem workspaceItem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
            .withTitle("Test publication with mandatory DOI 2")
            .withIssueDate("2017-10-17")
            .build();

        // disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + workspaceItem1.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist());

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + workspaceItem2.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors[?(@.message=='error.validation.test')]", allOf(
                contains(hasJsonPath("$.paths[0]", is("/sections/traditionalpageone/dc.title"))),
                contains(hasJsonPath("$.paths[1]", is("/sections/traditionalpageone/dc.identifier.doi"))))));
    }

    /**
     * Test step and global submission validation error
     *
     * @throws Exception
     */
    @Test
    public void stepAndGlobalValidationErrorsTest() throws Exception {
        context.turnOffAuthorisationSystem();

        // ** GIVEN **
        // 1. A community-collection structure with one parent community with
        // sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
            .withName("Parent Community")
            .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
            .withName("Sub Community")
            .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
            .withName("Collection 1")
            .withSubmitterGroup(eperson)
            .build();

        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem workspaceItem1 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
            .withTitle("Test publication with mandatory DOI 1")
            .withIssueDate("2017-10-17")
            .withDoiIdentifier("10.1000/182")
            .build();

        WorkspaceItem workspaceItem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
            .withTitle("Test publication with mandatory DOI 2")
            .build();

        // disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + workspaceItem1.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist());

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + workspaceItem2.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors[?(@.message=='error.validation.required')]",
                contains(hasJsonPath("$.paths[0]", is("/sections/traditionalpageone/dc.date.issued")))))
            .andExpect(jsonPath("$.errors[?(@.message=='error.validation.test')]", allOf(
                contains(hasJsonPath("$.paths[0]", is("/sections/traditionalpageone/dc.title"))),
                contains(hasJsonPath("$.paths[1]", is("/sections/traditionalpageone/dc.identifier.doi"))))));
    }

    @Test
    @Ignore
    /**
     * Test the metadata lookup
     *
     * @throws Exception
     */
    public void lookupDOIMetadataTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        String authToken = getAuthToken(admin.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .build();

        // try to add the web of science identifier
        List<Operation> addId = new ArrayList<Operation>();
        // create a list of values to use in add operation
        List<Map<String, String>> values = new ArrayList<Map<String, String>>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "10.1021/ac0354342");
        values.add(value);
        addId.add(new AddOperation("/sections/traditionalpageone/dc.identifier.doi", values));

        String patchBody = getPatchContent(addId);

        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                    .andExpect(status().isOk())
                    // testing lookup
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.doi'][0].value",
                        is("10.1021/ac0354342")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                        is("Multistep microreactions with proteins using electrocapture technology.")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.type'][1].value",
                        is("Journal Article")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                        is("2004-05-01")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                        is("Astorga-Wells, Juan")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][1].value",
                        is("Bergman, Tomas")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][2].value",
                        is(StringEscapeUtils.unescapeJava("J\\u00F6rnvall, Hans"))))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.issn'][0].value",
                        is("0003-2700")))
                    .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract'][0].value",
                        is("A method to perform multistep reactions by means of electroimmobilization of a " +
                           "target molecule in a microflow stream is presented. A target protein is captured " +
                           "by the opposing effects between the hydrodynamic and electric forces, after which " +
                           "another medium is injected into the system. The second medium carries enzymes or " +
                           "other reagents, which are brought into contact with the target protein and react." +
                           " The immobilization is reversed by disconnecting the electric field, " +
                           "upon which products are collected at the outlet of the device for analysis. On-line " +
                           "reduction, alkylation, and trypsin digestion of proteins is demonstrated and was" +
                           " monitored by MALDI mass spectrometry.")))
            ;

            // verify that the patch changes have been persisted
            getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                // testing lookup
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.doi'][0].value",
                    is("10.1021/ac0354342")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                    is("Multistep microreactions with proteins using electrocapture technology.")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.type'][1].value",
                    is("Journal Article")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                    is("2004-05-01")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                    is("Astorga-Wells, Juan")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][1].value",
                    is("Bergman, Tomas")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][2].value",
                    is(StringEscapeUtils.unescapeJava("J\\u00F6rnvall, Hans"))))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.issn'][0].value",
                    is("0003-2700")))
                .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract'][0].value",
                    is("A method to perform multistep reactions by means of electroimmobilization of a " +
                       "target molecule in a microflow stream is presented. A target protein is captured " +
                       "by the opposing effects between the hydrodynamic and electric forces, after which " +
                       "another medium is injected into the system. The second medium carries enzymes or " +
                       "other reagents, which are brought into contact with the target protein and react." +
                       " The immobilization is reversed by disconnecting the electric field, " +
                       "upon which products are collected at the outlet of the device for analysis. On-line " +
                       "reduction, alkylation, and trypsin digestion of proteins is demonstrated and was" +
                       " monitored by MALDI mass spectrometry.")))
            ;

    }

    @Test
    /**
     * Test the metadata lookup
     *
     * @throws Exception
     */
    public void lookupScopusMetadataTest() throws Exception {
        ConfigurationService configService = DSpaceServicesFactory.getInstance().getConfigurationService();
        String apikey = configService.getProperty("submission.lookup.scopus.apikey");
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        String authToken = getAuthToken(admin.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .build();

        // try to add the web of science identifier
        List<Operation> addId = new ArrayList<Operation>();
        // create a list of values to use in add operation
        List<Map<String, String>> values = new ArrayList<Map<String, String>>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "10.1016/j.joi.2016.11.006");
        values.add(value);
        addId.add(new AddOperation("/sections/traditionalpageone/dc.identifier.doi", values));

        String patchBody = getPatchContent(addId);

        if (apikey == null || apikey.equals("")) {
            getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                    .andExpect(status().isOk())
                    // testing lookup
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.doi'][0].value",
                        is("10.1016/j.joi.2016.11.006")));

                // verify that the patch changes have been persisted
                getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                    .andExpect(status().isOk())
                    // testing lookup
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.doi'][0].value",
                        is("10.1016/j.joi.2016.11.006")));
        } else {
            getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                    .andExpect(status().isOk())
                    // testing lookup
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.doi'][0].value",
                        is("10.1016/j.joi.2016.11.006")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                        is("Partial orders for zero-sum arrays with applications to network theory")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                        is("2017-02-01")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                        is("Liu Y.")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][1].value",
                        is("Rousseau R")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][2].value",
                        is("Egghe L.")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.issn'][0].value",
                        is("17511577")))
                    .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract'][0].value",
                        is(StringEscapeUtils.unescapeJava("\\u00A9 2016 Elsevier Ltd In this contribution" +
                           " we study partial orders in the set" +
                           " of zero-sum arrays. Concretely, these partial orders relate to local and" +
                           " global hierarchy and dominance theories. The exact relation between hierarchy" +
                           " and dominance curves is explained. Based on this investigation we design a" +
                           " new approach for measuring dominance or stated otherwise, power structures," +
                           " in networks. A new type of Lorenz curve to measure dominance or power is proposed," +
                           " and used to illustrate intrinsic characteristics of networks. The new curves," +
                           " referred to as D-curves are partly concave and partly convex. As such they do" +
                           " not satisfy Dalton's transfer principle. Most importantly, this article" +
                           " introduces a framework to compare different power structures as a whole." +
                           " It is shown that D-curves have several properties making them suitable to" +
                           " measure dominance. If dominance and being a subordinate are reversed, the" +
                           " dominance structure in a network is also reversed."))));

            // verify that the patch changes have been persisted
            getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                // testing lookup
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.doi'][0].value",
                        is("10.1016/j.joi.2016.11.006")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                        is("Partial orders for zero-sum arrays with applications to network theory")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                        is("2017-02-01")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                        is("Liu Y.")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][1].value",
                        is("Rousseau R")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][2].value",
                        is("Egghe L.")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.issn'][0].value",
                        is("17511577")))
                    .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract'][0].value",
                        is(StringEscapeUtils.unescapeJava("\\u00A9 2016 Elsevier Ltd In this contribution" +
                           " we study partial orders in the set" +
                           " of zero-sum arrays. Concretely, these partial orders relate to local and" +
                           " global hierarchy and dominance theories. The exact relation between hierarchy" +
                           " and dominance curves is explained. Based on this investigation we design a" +
                           " new approach for measuring dominance or stated otherwise, power structures," +
                           " in networks. A new type of Lorenz curve to measure dominance or power is proposed," +
                           " and used to illustrate intrinsic characteristics of networks. The new curves," +
                           " referred to as D-curves are partly concave and partly convex. As such they do" +
                           " not satisfy Dalton's transfer principle. Most importantly, this article" +
                           " introduces a framework to compare different power structures as a whole." +
                           " It is shown that D-curves have several properties making them suitable to" +
                           " measure dominance. If dominance and being a subordinate are reversed, the" +
                           " dominance structure in a network is also reversed."))));
        }
    }

    @Test
    /**
     * Test the metadata lookup
     *
     * @throws Exception
     */
    public void lookupWOSMetadataTest() throws Exception {
        ConfigurationService configService = DSpaceServicesFactory.getInstance().getConfigurationService();
        String wosUser = configService.getProperty("submission.lookup.webofknowledge.user");
        String wosPassword = configService.getProperty("submission.lookup.webofknowledge.password");
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        String authToken = getAuthToken(admin.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .build();

        // try to add the web of science identifier
        List<Operation> addId = new ArrayList<Operation>();
        // create a list of values to use in add operation
        List<Map<String, String>> values = new ArrayList<Map<String, String>>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "WOS:000270372400005");
        values.add(value);
        addId.add(new AddOperation("/sections/traditionalpageone/dc.identifier.isi", values));

        String patchBody = getPatchContent(addId);

        if (wosUser == null || wosUser.equals("") || wosPassword == null ||  wosPassword.equals("")) {
            getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                    .andExpect(status().isOk())
                    // testing lookup
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.isi'][0].value",
                        is("WOS:000270372400005")));

                // verify that the patch changes have been persisted
                getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                    .andExpect(status().isOk())
                    // testing lookup
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.isi'][0].value",
                        is("WOS:000270372400005")));
        } else {
            getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                    .andExpect(status().isOk())
                    // testing lookup
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.isi'][0].value",
                        is("WOS:000270372400005")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                        is("Individual Susceptibility to Cadmium Toxicity and Metallothionein Gene Polymorphisms:" +
                           " with References to Current Status of Occupational Cadmium Exposure")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.type'][0].value",
                        is("Article")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                        is("2009")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                        is("Miura, N")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.issn'][0].value",
                        is("0019-8366")));

            // verify that the patch changes have been persisted
            getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                // testing lookup
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.isi'][0].value",
                    is("WOS:000270372400005")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                    is("Individual Susceptibility to Cadmium Toxicity and Metallothionein Gene Polymorphisms:" +
                       " with References to Current Status of Occupational Cadmium Exposure")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.type'][0].value",
                    is("Article")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                    is("2009")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                    is("Miura, N")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.issn'][0].value",
                    is("0019-8366")));
        }
    }

    @Test
    /**
     * Test the update of metadata
     *
     * @throws Exception
     */
    public void patchUpdateMetadataTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2017-10-17")
                .withSubject("ExtraEntry")
                .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        // a simple patch to update an existent metadata
        List<Operation> updateTitle = new ArrayList<Operation>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "New Title");
        updateTitle.add(new ReplaceOperation("/sections/traditionalpageone/dc.title/0", value));

        String patchBody = getPatchContent(updateTitle);

        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
            .content(patchBody)
            .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.errors").doesNotExist())
                        .andExpect(jsonPath("$",
                                // check the new title and untouched values
                                Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject(witem,
                                        "New Title", "2017-10-17", "ExtraEntry"))));

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$",
                    Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject(witem,
                            "New Title", "2017-10-17", "ExtraEntry"))))
        ;
    }

    @Test
    public void patchUpdateMetadataForbiddenTest() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson eperson1 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson1@mail.com")
                .withPassword("qwerty01")
                .build();

        EPerson eperson2 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson2@mail.com")
                .withPassword("qwerty02")
                .build();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .build();

        context.setCurrentUser(eperson1);
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2019-01-01")
                .withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();

        List<Operation> updateIssueDate = new ArrayList<Operation>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "2020-01-01");
        updateIssueDate.add(new ReplaceOperation("/sections/traditionalpageone/dc.date.issued/0", value));

        String patchBody = getPatchContent(updateIssueDate);
        String tokenEperson2 = getAuthToken(eperson2.getEmail(), "qwerty02");
        getClient(tokenEperson2).perform(patch("/api/submission/workspaceitems/" + witem.getID())
            .content(patchBody)
            .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
            .andExpect(status().isForbidden());

        String tokenEperson1 = getAuthToken(eperson1.getEmail(), "qwerty01");
        getClient(tokenEperson1).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$",
                    Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject
                               (witem, "Workspace Item 1", "2019-01-01", "ExtraEntry"))));
    }

    public void patchReplaceMetadataOnItemStillInSubmissionTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .build();

        context.setCurrentUser(eperson);
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2017-10-17")
                .withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();

        List<Operation> updateTitle = new ArrayList<Operation>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "New Title");
        updateTitle.add(new ReplaceOperation("/metadata/dc.title/0", value));

        String patchBody = getPatchContent(updateTitle);
        UUID idItem = witem.getItem().getID();

        // Verify submitter cannot modify metadata via item PATCH. They must use submission forms.
        String tokenEperson = getAuthToken(eperson.getEmail(), password);
        getClient(tokenEperson).perform(patch("/api/core/items/" + idItem)
            .content(patchBody)
            .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
            .andExpect(status().isForbidden());

        String tokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(tokenAdmin).perform(get("/api/core/items/" + idItem))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", Matchers.is(ItemMatcher.matchItemWithTitleAndDateIssued
                  (witem.getItem(), "Workspace Item 1", "2017-10-17"))));
    }

    @Test
    public void patchAddMetadataOnItemStillInSubmissionTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .build();

        context.setCurrentUser(eperson);
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace")
                .withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();

        List<Operation> addIssueDate = new ArrayList<Operation>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "2017-10-17");
        addIssueDate.add(new ReplaceOperation("/metadata/dc.date.issued/0", value));

        String patchBody = getPatchContent(addIssueDate);
        UUID idItem = witem.getItem().getID();

        // Verify submitter cannot modify metadata via item PATCH. They must use submission forms.
        String tokenEperson = getAuthToken(eperson.getEmail(), password);
        getClient(tokenEperson).perform(patch("/api/core/items/" + idItem)
            .content(patchBody)
            .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
            .andExpect(status().isForbidden());

        String tokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(tokenAdmin).perform(get("/api/core/items/" + idItem))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasJsonPath("$.metadata", allOf(
              matchMetadata("dc.title", "Workspace")))))
        .andExpect(jsonPath("$.metadata.['dc.date.issued']").doesNotExist());
    }

    @Test
    public void patchUpdateMetadataUnAuthenticatedTest() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson eperson1 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson1@mail.com")
                .withPassword("qwerty01")
                .build();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .build();

        context.setCurrentUser(eperson1);
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2019-01-01")
                .withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();

        List<Operation> updateIssueDate = new ArrayList<Operation>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "2020-01-01");
        updateIssueDate.add(new ReplaceOperation("/sections/traditionalpageone/dc.date.issued/0", value));

        String patchBody = getPatchContent(updateIssueDate);
        getClient().perform(patch("/api/submission/workspaceitems/" + witem.getID())
            .content(patchBody)
            .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
            .andExpect(status().isUnauthorized());

        String tokenEperson1 = getAuthToken(eperson1.getEmail(), "qwerty01");
        getClient(tokenEperson1).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$",
                    Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject
                               (witem, "Workspace Item 1", "2019-01-01", "ExtraEntry"))));
    }

    @Test
    public void patchRemoveMetadataOnItemStillInSubmissionTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .build();

        context.setCurrentUser(eperson);
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace title")
                .withIssueDate("2017-10-17")
                .withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();

        List<Operation> removeTitle = new ArrayList<Operation>();
        removeTitle.add(new RemoveOperation("/metadata/dc.title/0"));

        String patchBody = getPatchContent(removeTitle);
        UUID idItem = witem.getItem().getID();

        // Verify submitter cannot modify metadata via item PATCH. They must use submission forms.
        String tokenEperson = getAuthToken(eperson.getEmail(), password);
        getClient(tokenEperson).perform(patch("/api/core/items/" + idItem)
            .content(patchBody)
            .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
            .andExpect(status().isForbidden());

        String tokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(tokenAdmin).perform(get("/api/core/items/" + idItem))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasJsonPath("$.metadata", allOf(
              matchMetadata("dc.title", "Workspace title"),
              matchMetadata("dc.date.issued", "2017-10-17")))));
    }

    @Test
    /**
     * Test delete of a metadata
     *
     * @throws Exception
     */
    public void patchDeleteMetadataTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2017-10-17")
                .withSubject("ExtraEntry")
                .build();

        WorkspaceItem witemMultipleSubjects = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2017-10-17")
                .withSubject("Subject1")
                .withSubject("Subject2")
                .withSubject("Subject3")
                .withSubject("Subject4")
                .build();

        WorkspaceItem witemWithTitleDateAndSubjects = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2017-10-17")
                .withSubject("Subject1")
                .withSubject("Subject2")
                .withSubject("Subject3")
                .withSubject("Subject4")
                .build();

        context.restoreAuthSystemState();

        // try to remove the title
        List<Operation> removeTitle = new ArrayList<Operation>();
        removeTitle.add(new RemoveOperation("/sections/traditionalpageone/dc.title/0"));

        String patchBody = getPatchContent(removeTitle);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors[?(@.message=='error.validation.required')]",
                                Matchers.contains(hasJsonPath("$.paths",
                                        Matchers.contains(
                                                hasJsonPath("$",
                                                        Matchers.is("/sections/traditionalpageone/dc.title")))))))
                            .andExpect(jsonPath("$",
                                    // check the new title and untouched values
                                    Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject(witem,
                                            null, "2017-10-17", "ExtraEntry"))));

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors[?(@.message=='error.validation.required')]",
                    Matchers.contains(
                            hasJsonPath("$.paths", Matchers.contains(
                                    hasJsonPath("$", Matchers.is("/sections/traditionalpageone/dc.title"))
                            )))))
            .andExpect(jsonPath("$",
                    Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject(witem,
                            null, "2017-10-17", "ExtraEntry"))))
        ;

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        // try to remove a metadata in a specific position
        List<Operation> removeMidSubject = new ArrayList<Operation>();
        removeMidSubject.add(new RemoveOperation("/sections/traditionalpagetwo/dc.subject/1"));

        patchBody = getPatchContent(removeMidSubject);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witemMultipleSubjects.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value", is("Subject1")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value", is("Subject3")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][2].value", is("Subject4")))
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witemMultipleSubjects.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value", is("Subject1")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value", is("Subject3")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][2].value", is("Subject4")))
        ;

        List<Operation> removeFirstSubject = new ArrayList<Operation>();
        removeFirstSubject.add(new RemoveOperation("/sections/traditionalpagetwo/dc.subject/0"));

        patchBody = getPatchContent(removeFirstSubject);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witemMultipleSubjects.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value", is("Subject3")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value", is("Subject4")))
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witemMultipleSubjects.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value", is("Subject3")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value", is("Subject4")))
        ;

        List<Operation> removeLastSubject = new ArrayList<Operation>();
        removeLastSubject.add(new RemoveOperation("/sections/traditionalpagetwo/dc.subject/1"));

        patchBody = getPatchContent(removeLastSubject);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witemMultipleSubjects.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value", is("Subject3")))
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witemMultipleSubjects.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value", is("Subject3")))
        ;

        List<Operation> removeFinalSubject = new ArrayList<Operation>();
        removeFinalSubject.add(new RemoveOperation("/sections/traditionalpagetwo/dc.subject/0"));

        patchBody = getPatchContent(removeFinalSubject);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witemMultipleSubjects.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject']").doesNotExist())
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witemMultipleSubjects.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject']").doesNotExist())
        ;

        // remove all the subjects with a single operation
        List<Operation> removeSubjectsAllAtOnce = new ArrayList<Operation>();
        removeSubjectsAllAtOnce.add(new RemoveOperation("/sections/traditionalpagetwo/dc.subject"));

        patchBody = getPatchContent(removeSubjectsAllAtOnce);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witemWithTitleDateAndSubjects.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject']").doesNotExist())
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witemWithTitleDateAndSubjects.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject']").doesNotExist())
        ;
    }

    @Test
    public void patchDeleteMetadataForbiddenTest() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson eperson1 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson1@mail.com")
                .withPassword("qwerty01")
                .build();

        EPerson eperson2 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson2@mail.com")
                .withPassword("qwerty02")
                .build();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .build();

        context.setCurrentUser(eperson1);
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2019-01-01")
                .withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();

        List<Operation> deleteIssueDate = new ArrayList<Operation>();
        deleteIssueDate.add(new RemoveOperation("/sections/traditionalpageone/dc.date.issued/0"));

        String patchBody = getPatchContent(deleteIssueDate);
        String tokenEperson2 = getAuthToken(eperson2.getEmail(), "qwerty02");
        getClient(tokenEperson2).perform(patch("/api/submission/workspaceitems/" + witem.getID())
            .content(patchBody)
            .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
            .andExpect(status().isForbidden());

        String tokenEperson1 = getAuthToken(eperson1.getEmail(), "qwerty01");
        getClient(tokenEperson1).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$",
                    Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject
                               (witem, "Workspace Item 1", "2019-01-01", "ExtraEntry"))));
    }

    @Test
    public void patchDeleteMetadataUnAuthenticatedTest() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson eperson1 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson1@mail.com")
                .withPassword("qwerty01")
                .build();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .build();

        context.setCurrentUser(eperson1);
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2019-01-01")
                .withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();

        List<Operation> deleteIssueDate = new ArrayList<Operation>();
        deleteIssueDate.add(new RemoveOperation("/sections/traditionalpageone/dc.date.issued/0"));

        String patchBody = getPatchContent(deleteIssueDate);
        getClient().perform(patch("/api/submission/workspaceitems/" + witem.getID())
            .content(patchBody)
            .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
            .andExpect(status().isUnauthorized());

        String tokenEperson1 = getAuthToken(eperson1.getEmail(), "qwerty01");
        getClient(tokenEperson1).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$",
                    Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject
                               (witem, "Workspace Item 1", "2019-01-01", "ExtraEntry"))));
    }

    @Test
    /**
     * Test delete of a section
     *
     * @throws Exception
     */
    public void patchDeleteSectionTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        String authToken = getAuthToken(admin.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2017-10-17")
                .withSubject("Subject1")
                .withSubject("Subject2")
                .withSubject("Subject3")
                .withSubject("Subject4")
                .withAbstract("This is a sample abstract")
                .build();

        // remove entire section metadata
        List<Operation> removeSubjectsAllAtOnce = new ArrayList<Operation>();
        removeSubjectsAllAtOnce.add(new RemoveOperation("/sections/traditionalpagetwo"));

        String patchBody = getPatchContent(removeSubjectsAllAtOnce);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject']").doesNotExist())
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract']")
                                    .doesNotExist())
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject']").doesNotExist())
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract']").doesNotExist())
        ;
    }

    @Test
    /**
     * Test the addition of metadata
     *
     * @throws Exception
     */
    public void patchAddMetadataTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withIssueDate("2017-10-17")
                .withSubject("ExtraEntry")
                .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        // try to add the title
        List<Operation> operations = new ArrayList<Operation>();
        // create a list of values to use in add operation
        List<Map<String, String>> titelValues = new ArrayList<Map<String, String>>();
        List<Map<String, String>> uriValues = new ArrayList<Map<String, String>>();
        Map<String, String> value = new HashMap<String, String>();
        Map<String, String> value2 = new HashMap<String, String>();
        value.put("value", "New Title");
        value2.put("value", "https://www.dspace.org");
        titelValues.add(value);
        uriValues.add(value2);
        operations.add(new AddOperation("/sections/traditionalpageone/dc.title", titelValues));
        operations.add(new AddOperation("/sections/traditionalpageone/dc.identifier.uri", uriValues));

        String patchBody = getPatchContent(operations);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$",
                                    // check if the new title if back and the other values untouched
                                    Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject(witem,
                                            "New Title", "2017-10-17", "ExtraEntry"))))
                            .andExpect(jsonPath("$", Matchers.allOf(
                                    hasJsonPath("$.sections.traditionalpageone['dc.identifier.uri'][0].value",
                                             is("https://www.dspace.org")))));

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$",
                    Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject(witem,
                            "New Title", "2017-10-17", "ExtraEntry"))))
            .andExpect(jsonPath("$", Matchers.allOf(
                    hasJsonPath("$.sections.traditionalpageone['dc.identifier.uri'][0].value",
                             is("https://www.dspace.org")))))
        ;
    }

    @Test
    public void patchAddMetadataUpdateExistValueTest() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson eperson1 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson1@mail.com")
                .withPassword("qwerty01")
                .build();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        context.setCurrentUser(eperson1);
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Workspace Item 1")
                .withIssueDate("2017-10-17")
                .withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();

        List<Operation> addTitle = new ArrayList<Operation>();
        List<Map<String, String>> values = new ArrayList<Map<String, String>>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "New Title");
        values.add(value);
        addTitle.add(new AddOperation("/sections/traditionalpageone/dc.title", values));

        String patchBody = getPatchContent(addTitle);
        String authToken = getAuthToken(eperson1.getEmail(), "qwerty01");
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
            .content(patchBody)
            .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
            .andExpect(status().isOk());

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$",
                    Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject(witem,
                            "New Title", "2017-10-17", "ExtraEntry"))))
        ;
    }

    @Test
    public void patchAddMetadataUnAuthenticatedTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withIssueDate("2020-01-13")
                .withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();

        List<Operation> addTitle = new ArrayList<Operation>();
        List<Map<String, String>> values = new ArrayList<Map<String, String>>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "New Title");
        values.add(value);
        addTitle.add(new AddOperation("/sections/traditionalpageone/dc.title", values));

        String patchBody = getPatchContent(addTitle);
        getClient().perform(patch("/api/submission/workspaceitems/" + witem.getID())
                   .content(patchBody)
                   .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                   .andExpect(status().isUnauthorized());

        String authToken = getAuthToken(admin.getEmail(), password);
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$",
                    Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject
                               (witem, null, "2020-01-13", "ExtraEntry"))));
    }

    @Test
    public void patchAddMetadataForbiddenTest() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson eperson1 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson1@mail.com")
                .withPassword("qwerty01")
                .build();

        EPerson eperson2 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson2@mail.com")
                .withPassword("qwerty02")
                .build();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .build();

        context.setCurrentUser(eperson1);
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withIssueDate("2019-11-25")
                .withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();

        List<Operation> addTitle = new ArrayList<Operation>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "New Title");
        addTitle.add(new AddOperation("/sections/traditionalpageone/dc.title", value));

        String patchBody = getPatchContent(addTitle);
        String tokenEperson2 = getAuthToken(eperson2.getEmail(), "qwerty02");
        getClient(tokenEperson2).perform(patch("/api/submission/workspaceitems/" + witem.getID())
            .content(patchBody)
            .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
            .andExpect(status().isForbidden());

        String tokenEperson1 = getAuthToken(eperson1.getEmail(), "qwerty01");
        getClient(tokenEperson1).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$",
                    Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject
                               (witem, null, "2019-11-25", "ExtraEntry"))));
    }

    @Test
    /**
     * Test the addition of metadata
     *
     * @throws Exception
     */
    public void patchAddMultipleMetadataValuesTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem")
                .withIssueDate("2017-10-17")
                .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        // try to add multiple subjects at once
        List<Operation> addSubjects = new ArrayList<Operation>();
        // create a list of values to use in add operation
        List<Map<String, String>> values = new ArrayList<Map<String, String>>();
        Map<String, String> value1 = new HashMap<String, String>();
        value1.put("value", "Subject1");
        Map<String, String> value2 = new HashMap<String, String>();
        value2.put("value", "Subject2");
        values.add(value1);
        values.add(value2);

        addSubjects.add(new AddOperation("/sections/traditionalpagetwo/dc.subject", values));

        String patchBody = getPatchContent(addSubjects);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value",
                                    is("Subject1")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value",
                                    is("Subject2")))
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value", is("Subject1")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value", is("Subject2")))
        ;

        // add a subject in the first position
        List<Operation> addFirstSubject = new ArrayList<Operation>();
        Map<String, String> firstSubject = new HashMap<String, String>();
        firstSubject.put("value", "First Subject");

        addFirstSubject.add(new AddOperation("/sections/traditionalpagetwo/dc.subject/0", firstSubject));

        patchBody = getPatchContent(addFirstSubject);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value",
                                    is("First Subject")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value",
                                    is("Subject1")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][2].value",
                                    is("Subject2")))
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value", is("First Subject")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value", is("Subject1")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][2].value", is("Subject2")))
        ;

        // add a subject in a central position
        List<Operation> addMidSubject = new ArrayList<Operation>();
        Map<String, String> midSubject = new HashMap<String, String>();
        midSubject.put("value", "Mid Subject");

        addMidSubject.add(new AddOperation("/sections/traditionalpagetwo/dc.subject/2", midSubject));

        patchBody = getPatchContent(addMidSubject);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value",
                                    is("First Subject")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value",
                                    is("Subject1")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][2].value",
                                    is("Mid Subject")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][3].value",
                                    is("Subject2")))
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value", is("First Subject")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value", is("Subject1")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][2].value", is("Mid Subject")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][3].value", is("Subject2")))
        ;

        // append a last subject without specifying the index
        List<Operation> addLastSubject = new ArrayList<Operation>();
        Map<String, String> lastSubject = new HashMap<String, String>();
        lastSubject.put("value", "Last Subject");

        addLastSubject.add(new AddOperation("/sections/traditionalpagetwo/dc.subject/4", lastSubject));

        patchBody = getPatchContent(addLastSubject);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value",
                                    is("First Subject")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value",
                                    is("Subject1")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][2].value",
                                    is("Mid Subject")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][3].value",
                                    is("Subject2")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][4].value",
                                    is("Last Subject")))
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value", is("First Subject")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value", is("Subject1")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][2].value", is("Mid Subject")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][3].value", is("Subject2")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][4].value", is("Last Subject")))
        ;

        // append a last subject without specifying the index
        List<Operation> addFinalSubject = new ArrayList<Operation>();
        Map<String, String> finalSubject = new HashMap<String, String>();
        finalSubject.put("value", "Final Subject");

        addFinalSubject.add(new AddOperation("/sections/traditionalpagetwo/dc.subject/-", finalSubject));

        patchBody = getPatchContent(addFinalSubject);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value",
                                    is("First Subject")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value",
                                    is("Subject1")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][2].value",
                                    is("Mid Subject")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][3].value",
                                    is("Subject2")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][4].value",
                                    is("Last Subject")))
                            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][5].value",
                                    is("Final Subject")))
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][0].value", is("First Subject")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][1].value", is("Subject1")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][2].value", is("Mid Subject")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][3].value", is("Subject2")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][4].value", is("Last Subject")))
            .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject'][5].value", is("Final Subject")))
        ;
    }

    @Test
    /**
     * Test the acceptance of the deposit license
     *
     * @throws Exception
     */
    public void patchAcceptLicenseTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem")
                .withIssueDate("2017-10-17")
                .build();

        WorkspaceItem witem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem 2")
                .withIssueDate("2017-10-17")
                .build();

        WorkspaceItem witem3 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem 3")
                .withIssueDate("2017-10-17")
                .build();

        WorkspaceItem witem4 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem 4")
                .withIssueDate("2017-10-17")
                .build();

        context.restoreAuthSystemState();

        // check that our workspaceitems come without a license (all are build in the same way, just check the first)
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.license.granted",
                    is(false)))
            .andExpect(jsonPath("$.sections.license.acceptanceDate").isEmpty())
            .andExpect(jsonPath("$.sections.license.url").isEmpty())
        ;

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        // try to grant the license with an add operation
        List<Operation> addGrant = new ArrayList<Operation>();
        addGrant.add(new AddOperation("/sections/license/granted", true));

        String patchBody = getPatchContent(addGrant);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.license.granted",
                                    is(true)))
                            .andExpect(jsonPath("$.sections.license.acceptanceDate").isNotEmpty())
                            .andExpect(jsonPath("$.sections.license.url").isNotEmpty())
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.license.granted",
                    is(true)))
            .andExpect(jsonPath("$.sections.license.acceptanceDate").isNotEmpty())
            .andExpect(jsonPath("$.sections.license.url").isNotEmpty())
        ;

        // try to grant the license with an add operation supplying a string instead than a boolean
        List<Operation> addGrantString = new ArrayList<Operation>();
        addGrantString.add(new AddOperation("/sections/license/granted", "true"));

        patchBody = getPatchContent(addGrantString);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem2.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.license.granted",
                                    is(true)))
                            .andExpect(jsonPath("$.sections.license.acceptanceDate").isNotEmpty())
                            .andExpect(jsonPath("$.sections.license.url").isNotEmpty())
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem2.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.license.granted",
                    is(true)))
            .andExpect(jsonPath("$.sections.license.acceptanceDate").isNotEmpty())
            .andExpect(jsonPath("$.sections.license.url").isNotEmpty())
        ;

        // try to grant the license with a replace operation
        List<Operation> replaceGrant = new ArrayList<Operation>();
        replaceGrant.add(new ReplaceOperation("/sections/license/granted", true));

        patchBody = getPatchContent(replaceGrant);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem3.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.license.granted",
                                    is(true)))
                            .andExpect(jsonPath("$.sections.license.acceptanceDate").isNotEmpty())
                            .andExpect(jsonPath("$.sections.license.url").isNotEmpty())
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem3.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.license.granted",
                    is(true)))
            .andExpect(jsonPath("$.sections.license.acceptanceDate").isNotEmpty())
            .andExpect(jsonPath("$.sections.license.url").isNotEmpty())
        ;

        // try to grant the license with a replace operation supplying a string
        List<Operation> replaceGrantString = new ArrayList<Operation>();
        replaceGrant.add(new ReplaceOperation("/sections/license/granted", "true"));

        patchBody = getPatchContent(replaceGrant);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem4.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.license.granted",
                                    is(true)))
                            .andExpect(jsonPath("$.sections.license.acceptanceDate").isNotEmpty())
                            .andExpect(jsonPath("$.sections.license.url").isNotEmpty())
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem4.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.license.granted",
                    is(true)))
            .andExpect(jsonPath("$.sections.license.acceptanceDate").isNotEmpty())
            .andExpect(jsonPath("$.sections.license.url").isNotEmpty())
        ;
    }

    @Test
    /**
     * Test the reject of the deposit license
     *
     * @throws Exception
     */
    public void patchRejectLicenseTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();
        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem")
                .withIssueDate("2017-10-17")
                .grantLicense()
                .build();

        WorkspaceItem witem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem 2")
                .withIssueDate("2017-10-17")
                .grantLicense()
                .build();

        WorkspaceItem witem3 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem 3")
                .withIssueDate("2017-10-17")
                .grantLicense()
                .build();

        WorkspaceItem witem4 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem 4")
                .withIssueDate("2017-10-17")
                .grantLicense()
                .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        // check that our workspaceitems come with a license (all are build in the same way, just check the first)
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.license.granted",
                    is(true)))
            .andExpect(jsonPath("$.sections.license.acceptanceDate").isNotEmpty())
            .andExpect(jsonPath("$.sections.license.url").isNotEmpty())
        ;

        // try to reject the license with an add operation
        List<Operation> addGrant = new ArrayList<Operation>();
        addGrant.add(new AddOperation("/sections/license/granted", false));

        String patchBody = getPatchContent(addGrant);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.license.granted",
                                    is(false)))
                            .andExpect(jsonPath("$.sections.license.acceptanceDate").isEmpty())
                            .andExpect(jsonPath("$.sections.license.url").isEmpty())
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.license.granted",
                    is(false)))
            .andExpect(jsonPath("$.sections.license.acceptanceDate").isEmpty())
            .andExpect(jsonPath("$.sections.license.url").isEmpty())
        ;

        // try to reject the license with an add operation supplying a string instead than a boolean
        List<Operation> addGrantString = new ArrayList<Operation>();
        addGrantString.add(new AddOperation("/sections/license/granted", "false"));

        patchBody = getPatchContent(addGrantString);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem2.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.license.granted",
                                    is(false)))
                            .andExpect(jsonPath("$.sections.license.acceptanceDate").isEmpty())
                            .andExpect(jsonPath("$.sections.license.url").isEmpty())
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem2.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.license.granted",
                    is(false)))
            .andExpect(jsonPath("$.sections.license.acceptanceDate").isEmpty())
            .andExpect(jsonPath("$.sections.license.url").isEmpty())
        ;

        // try to reject the license with a replace operation
        List<Operation> replaceGrant = new ArrayList<Operation>();
        replaceGrant.add(new ReplaceOperation("/sections/license/granted", false));

        patchBody = getPatchContent(replaceGrant);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem3.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.license.granted",
                                    is(false)))
                            .andExpect(jsonPath("$.sections.license.acceptanceDate").isEmpty())
                            .andExpect(jsonPath("$.sections.license.url").isEmpty())
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem3.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.license.granted",
                    is(false)))
            .andExpect(jsonPath("$.sections.license.acceptanceDate").isEmpty())
            .andExpect(jsonPath("$.sections.license.url").isEmpty())
        ;

        // try to reject the license with a replace operation supplying a string
        List<Operation> replaceGrantString = new ArrayList<Operation>();
        replaceGrant.add(new ReplaceOperation("/sections/license/granted", "false"));

        patchBody = getPatchContent(replaceGrant);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem4.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.errors").doesNotExist())
                            .andExpect(jsonPath("$.sections.license.granted",
                                    is(false)))
                            .andExpect(jsonPath("$.sections.license.acceptanceDate").isEmpty())
                            .andExpect(jsonPath("$.sections.license.url").isEmpty())
        ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem4.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.license.granted",
                    is(false)))
            .andExpect(jsonPath("$.sections.license.acceptanceDate").isEmpty())
            .andExpect(jsonPath("$.sections.license.url").isEmpty())
        ;

    }

    @Test
    /**
     * Test update of bitstream metadata in the upload section
     *
     * @throws Exception
     */
    public void patchUploadTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        String authToken = getAuthToken(eperson.getEmail(), password);

        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem")
                .withIssueDate("2017-10-17")
                .withFulltext("simple-article.pdf", "/local/path/simple-article.pdf", pdf)
                .build();

        context.restoreAuthSystemState();

        // check the file metadata
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.source'][0].value",
                    is("/local/path/simple-article.pdf")))
            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value",
                    is("simple-article.pdf")))
        ;

        // try to change the filename and add a description
        List<Operation> addOpts = new ArrayList<Operation>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "newfilename.pdf");
        Map<String, String> valueDesc  = new HashMap<String, String>();
        valueDesc.put("value", "Description");
        List valueDescs = new ArrayList();
        valueDescs.add(valueDesc);
        addOpts.add(new AddOperation("/sections/upload/files/0/metadata/dc.title/0", value));
        addOpts.add(new AddOperation("/sections/upload/files/0/metadata/dc.description", valueDescs));

        String patchBody = getPatchContent(addOpts);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            // is the source still here?
                            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.source'][0].value",
                                    is("/local/path/simple-article.pdf")))
                            // check the new filename
                            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value",
                                    is("newfilename.pdf")))
                            // check the description
                            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.description'][0].value",
                                    is("Description")))
        ;

        // check that changes persist
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.source'][0].value",
                    is("/local/path/simple-article.pdf")))
            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value",
                    is("newfilename.pdf")))
            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.description'][0].value",
                    is("Description")))
        ;

        // try to remove the description
        List<Operation> removeOpts = new ArrayList<Operation>();
        removeOpts.add(new RemoveOperation("/sections/upload/files/0/metadata/dc.description"));

        patchBody = getPatchContent(removeOpts);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            // check the filename still here
                            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value",
                                    is("newfilename.pdf")))
                            // check the removed description
                            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.description']").doesNotExist())
        ;

        // check that changes persist
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.source'][0].value",
                    is("/local/path/simple-article.pdf")))
            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value",
                    is("newfilename.pdf")))
            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.description']").doesNotExist())        ;

        // try to update the filename with an update opt
        List<Operation> updateOpts = new ArrayList<Operation>();
        Map<String, String> updateValue = new HashMap<String, String>();
        updateValue.put("value", "another-filename.pdf");
        updateOpts.add(new ReplaceOperation("/sections/upload/files/0/metadata/dc.title/0", updateValue));

        patchBody = getPatchContent(updateOpts);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isOk())
                            // check the filename still here
                            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value",
                                    is("another-filename.pdf")))
        ;

        // check that changes persist
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value",
                    is("another-filename.pdf")))
        ;
    }

    @Test
    public void patchUploadAddAndRemoveAccessConditionTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();

        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();

        Collection collection1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .build();

        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, collection1)
                .withTitle("Test WorkspaceItem")
                .withIssueDate("2019-10-01")
                .withFulltext("simple-article.pdf", "/local/path/simple-article.pdf", pdf)
                .build();

        context.restoreAuthSystemState();

        // date
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        Date startDate = new Date();
        String startDateStr = dateFmt.format(startDate);

        // create a list of values to use in add operation
        List<Operation> addAccessCondition = new ArrayList<>();
        Map<String, String> value = new HashMap<>();
        value.put("name", "embargo");
        value.put("startDate", startDateStr);
        addAccessCondition.add(new AddOperation("/sections/upload/files/0/accessConditions/-", value));

        String patchBody = getPatchContent(addAccessCondition);
        String authToken = getAuthToken(eperson.getEmail(), password);

        getClient(authToken)
            .perform(
                patch("/api/submission/workspaceitems/" + witem.getID())
                    .content(patchBody)
                    .contentType(MediaType.APPLICATION_JSON_PATCH_JSON)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].name", is("embargo")))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].startDate", is(startDateStr)))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].endDate", nullValue()));

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].name", is("embargo")))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].startDate", is(startDateStr)))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].endDate", nullValue()));

        // create a list of values to use in remove operation
        List<Operation> removeAccessCondition = new ArrayList<>();
        removeAccessCondition.add(new RemoveOperation("/sections/upload/files/0/accessConditions"));

        // remove and verify that access conditions are removed
        String patchReplaceBody = getPatchContent(removeAccessCondition);
        getClient(authToken)
            .perform(
                patch("/api/submission/workspaceitems/" + witem.getID())
                    .content(patchReplaceBody)
                    .contentType(MediaType.APPLICATION_JSON_PATCH_JSON)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions", empty()));

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions", empty()));
    }

    @Test
    /**
     * Test the upload of files in the upload over section
     *
     * @throws Exception
     */
    public void uploadTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem")
                .withIssueDate("2017-10-17")
                .build();

        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        final MockMultipartFile pdfFile = new MockMultipartFile("file", "/local/path/simple-article.pdf",
                "application/pdf", pdf);

        context.restoreAuthSystemState();

        // upload the file in our workspaceitem
        getClient(authToken).perform(fileUpload("/api/submission/workspaceitems/" + witem.getID())
                .file(pdfFile))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value",
                            is("simple-article.pdf")))
                    .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.source'][0].value",
                            is("/local/path/simple-article.pdf")))
        ;

        // check the file metadata
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value",
                    is("simple-article.pdf")))
            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.source'][0].value",
                    is("/local/path/simple-article.pdf")))
        ;
    }

    @Test
    public void uploadUnAuthenticatedTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem")
                .withIssueDate("2017-10-17")
                .build();

        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        final MockMultipartFile pdfFile = new MockMultipartFile("file", "/local/path/simple-article.pdf",
                "application/pdf", pdf);

        context.restoreAuthSystemState();

        // upload the file in our workspaceitem
        getClient().perform(fileUpload("/api/submission/workspaceitems/" + witem.getID())
                .file(pdfFile))
                .andExpect(status().isUnauthorized());

        String authToken = getAuthToken(admin.getEmail(), password);
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(0)));
    }

    @Test
    public void uploadForbiddenTest() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson eperson1 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson1@mail.com")
                .withPassword("qwerty01")
                .build();

        EPerson eperson2 = EPersonBuilder.createEPerson(context)
                .withEmail("eperson2@mail.com")
                .withPassword("qwerty02")
                .build();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        context.setCurrentUser(eperson1);
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("WorkspaceItem")
                .withIssueDate("2019-10-27")
                .build();

        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        final MockMultipartFile pdfFile = new MockMultipartFile("file", "/local/path/simple-article.pdf",
                "application/pdf", pdf);

        context.restoreAuthSystemState();

        // upload the file in our workspaceitem
        String authToken = getAuthToken(eperson2.getEmail(), "qwerty02");
        getClient(authToken).perform(fileUpload("/api/submission/workspaceitems/" + witem.getID())
                .file(pdfFile))
                .andExpect(status().isForbidden());

        String authToken2 = getAuthToken(eperson1.getEmail(), "qwerty01");
        getClient(authToken2).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.upload.files", hasSize(0)));
    }

    @Test
    public void createWorkspaceWithFiles_UploadRequired() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
            .withName("Parent Community")
            .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
            .withName("Sub Community")
            .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
            .withTitle("Test WorkspaceItem")
            .withIssueDate("2017-10-17")
            .build();

        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        final MockMultipartFile pdfFile = new MockMultipartFile("file", "/local/path/simple-article.pdf",
            "application/pdf", pdf);

        context.restoreAuthSystemState();
        // upload the file in our workspaceitem
        getClient(authToken).perform(fileUpload("/api/submission/workspaceitems/" + witem.getID())
            .file(pdfFile))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value",
                is("simple-article.pdf")))
            .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.source'][0].value",
                is("/local/path/simple-article.pdf")))
        ;

        //Verify there are no errors since file was uploaded (with upload required set to true)
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    public void createWorkspaceWithoutFiles_UploadRequired() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
            .withName("Parent Community")
            .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
            .withName("Sub Community")
            .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
            .withTitle("Test WorkspaceItem")
            .withIssueDate("2017-10-17")
            .build();

        context.restoreAuthSystemState();
        //Verify there is an error since no file was uploaded (with upload required set to true)
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").isNotEmpty())
            .andExpect(jsonPath("$.errors[?(@.message=='error.validation.filerequired')]",
                Matchers.contains(
                    hasJsonPath("$.paths", Matchers.contains(
                        hasJsonPath("$", Matchers.is("/sections/upload"))
                    )))));
    }

    @Test
    public void createWorkspaceItemFromExternalSources() throws Exception {
        //We turn off the authorization system in order to create the structure as defined below
        context.turnOffAuthorisationSystem();
        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        context.restoreAuthSystemState();

        Integer workspaceItemId = null;
        try {

        ObjectMapper mapper = new ObjectMapper();
        // You have to be an admin to create an Item from an ExternalDataObject
        String token = getAuthToken(admin.getEmail(), password);
        MvcResult mvcResult = getClient(token).perform(post("/api/submission/workspaceitems?owningCollection="
                                                                + col1.getID().toString())
                                                           .contentType(parseMediaType(TEXT_URI_LIST_VALUE))
                                                           .content("https://localhost:8080/server/api/integration/" +
                                                                        "externalsources/mock/entryValues/one"))
                                            .andExpect(status().isCreated()).andReturn();

        String content = mvcResult.getResponse().getContentAsString();
        Map<String,Object> map = mapper.readValue(content, Map.class);
        workspaceItemId = (Integer) map.get("id");
        String itemUuidString = String.valueOf(((Map) ((Map) map.get("_embedded")).get("item")).get("uuid"));

        getClient(token).perform(get("/api/submission/workspaceitems/" + workspaceItemId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$", Matchers.allOf(
                            hasJsonPath("$.id", is(workspaceItemId)),
                            hasJsonPath("$.type", is("workspaceitem")),
                            hasJsonPath("$._embedded.item", Matchers.allOf(
                                hasJsonPath("$.id", is(itemUuidString)),
                                hasJsonPath("$.uuid", is(itemUuidString)),
                                hasJsonPath("$.type", is("item")),
                                hasJsonPath("$.metadata", Matchers.allOf(
                                    MetadataMatcher.matchMetadata("dc.contributor.author", "Donald, Smith")
                                )))))
                        ));
        } finally {
            WorkspaceItemBuilder.deleteWorkspaceItem(workspaceItemId);
        }
    }

    @Test
    public void createWorkspaceItemFromExternalSourcesNoOwningCollectionUuidBadRequest() throws Exception {
        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(post("/api/submission/workspaceitems")
                                     .contentType(parseMediaType(
                                         TEXT_URI_LIST_VALUE))
                                     .content("https://localhost:8080/server/api/integration/externalsources/" +
                                                "mock/entryValues/one"))
                        .andExpect(status().isBadRequest());
    }

    @Test
    public void createWorkspaceItemFromExternalSourcesRandomOwningCollectionUuidBadRequest() throws Exception {
        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(post("/api/submission/workspaceitems?owningCollection=" + UUID.randomUUID())
                                     .contentType(parseMediaType(
                                         TEXT_URI_LIST_VALUE))
                                     .content("https://localhost:8080/server/api/integration/externalsources/" +
                                                  "mock/entryValues/one"))
                        .andExpect(status().isBadRequest());
    }

    @Test
    public void createWorkspaceItemFromExternalSourcesWrongUriList() throws Exception {
        //We turn off the authorization system in order to create the structure as defined below
        context.turnOffAuthorisationSystem();
        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        context.restoreAuthSystemState();

        ObjectMapper mapper = new ObjectMapper();
        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(post("/api/submission/workspaceitems?owningCollection="
                                          + col1.getID().toString())
                                     .contentType(parseMediaType(
                                         TEXT_URI_LIST_VALUE))
                                     .content("https://localhost:8080/server/mock/mock/mock/" +
                                                  "mock/entryValues/one")).andExpect(status().isBadRequest());
    }

    @Test
    public void createWorkspaceItemFromExternalSourcesWrongSourceBadRequest() throws Exception {
        //We turn off the authorization system in order to create the structure as defined below
        context.turnOffAuthorisationSystem();
        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        context.restoreAuthSystemState();

        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(post("/api/submission/workspaceitems?owningCollection="
                                          + col1.getID().toString())
                                     .contentType(parseMediaType(
                                         TEXT_URI_LIST_VALUE))
                                     .content("https://localhost:8080/server/api/integration/externalsources/" +
                                                  "mockWrongSource/entryValues/one"))
                        .andExpect(status().isBadRequest());

    }

    @Test
    public void createWorkspaceItemFromExternalSourcesWrongIdResourceNotFound() throws Exception {
        //We turn off the authorization system in order to create the structure as defined below
        context.turnOffAuthorisationSystem();
        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        context.restoreAuthSystemState();

        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(post("/api/submission/workspaceitems?owningCollection="
                                          + col1.getID().toString())
                                     .contentType(parseMediaType(
                                         TEXT_URI_LIST_VALUE))
                                     .content("https://localhost:8080/server/api/integration/externalsources/" +
                                                  "mock/entryValues/azeazezaezeaz"))
                        .andExpect(status().is(404));

    }

    @Test
    public void createWorkspaceItemFromExternalSourcesForbidden() throws Exception {
        //We turn off the authorization system in order to create the structure as defined below
        context.turnOffAuthorisationSystem();
        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        context.restoreAuthSystemState();

        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(post("/api/submission/workspaceitems?owningCollection="
                                          + col1.getID().toString())
                                     .contentType(parseMediaType(
                                         TEXT_URI_LIST_VALUE))
                                     .content("https://localhost:8080/server/api/integration/externalsources/" +
                                                  "mock/entryValues/one"))
                        .andExpect(status().isForbidden());
    }

    @Test
    public void createWorkspaceItemFromExternalSourcesUnauthorized() throws Exception {
        //We turn off the authorization system in order to create the structure as defined below
        context.turnOffAuthorisationSystem();
        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        context.restoreAuthSystemState();

        getClient().perform(post("/api/submission/workspaceitems?owningCollection="
                                     + col1.getID().toString())
                                .contentType(parseMediaType(
                                    TEXT_URI_LIST_VALUE))
                                .content("https://localhost:8080/server/api/integration/externalsources/" +
                                             "mock/entryValues/one"))
                   .andExpect(status().isUnauthorized());
    }

    @Test
    public void createWorkspaceItemFromExternalSourcesNonAdminWithPermission() throws Exception {
        //We turn off the authorization system in order to create the structure as defined below
        context.turnOffAuthorisationSystem();
        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1")
                .withSubmitterGroup(eperson).build();

        context.restoreAuthSystemState();

        Integer workspaceItemId = null;
        AtomicReference<Integer> idRef = new AtomicReference<>();

        try {

        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(post("/api/submission/workspaceitems")
                            .param("owningCollection", col1.getID().toString())
                            .contentType(parseMediaType(TEXT_URI_LIST_VALUE))
                            .content("https://localhost:8080/server/api/integration/externalsources/" +
                                                          "mock/entryValues/one"))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$._embedded.collection.id", is(col1.getID().toString())))
                            .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(), "$.id")));
        workspaceItemId = idRef.get();

        getClient(token).perform(get("/api/submission/workspaceitems/" + workspaceItemId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", Matchers.allOf(
            hasJsonPath("$.id", is(workspaceItemId)),
            hasJsonPath("$.type", is("workspaceitem")),
            hasJsonPath("$._embedded.item", Matchers.allOf(
                hasJsonPath("$.metadata", Matchers.allOf(
                    MetadataMatcher.matchMetadata("dc.contributor.author", "Donald, Smith")
            )))),
            hasJsonPath("$._embedded.collection", Matchers.allOf(
                hasJsonPath("$.id", is(col1.getID().toString())
            )))
        )));

        } finally {
            WorkspaceItemBuilder.deleteWorkspaceItem(idRef.get());
        }

    }

    @Test
    public void findByItemUuidTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        //2. a workspace item
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                                  .withTitle("Workspace Item 1")
                                                  .withIssueDate("2017-10-17")
                                                  .withAuthor("Smith, Donald").withAuthor("Doe, John")
                                                  .withSubject("ExtraEntry")
                                                  .build();

        context.restoreAuthSystemState();
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/submission/workspaceitems/search/item")
                                .param("uuid", String.valueOf(witem.getItem().getID())))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$",
                                       Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject
                                           (witem, "Workspace Item 1", "2017-10-17",
                                            "ExtraEntry"))));
    }

    @Test
    public void findByItemUuidMissingParameterTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        //2. a workspace item
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                                  .withTitle("Workspace Item 1")
                                                  .withIssueDate("2017-10-17")
                                                  .withAuthor("Smith, Donald").withAuthor("Doe, John")
                                                  .withSubject("ExtraEntry")
                                                  .build();
        context.restoreAuthSystemState();
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/submission/workspaceitems/search/item"))
                   .andExpect(status().isBadRequest());
    }

    @Test
    public void findByItemUuidDoesntExistTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        Item item = ItemBuilder.createItem(context, col1).build();
        //2. a workspace item
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                                  .withTitle("Workspace Item 1")
                                                  .withIssueDate("2017-10-17")
                                                  .withAuthor("Smith, Donald").withAuthor("Doe, John")
                                                  .withSubject("ExtraEntry")
                                                  .build();
        context.restoreAuthSystemState();
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/submission/workspaceitems/search/item")
                                .param("uuid", String.valueOf(item.getID())))
                   .andExpect(status().isNoContent());
    }

    @Test
    public void findByItemUuidForbiddenTest() throws Exception {
        context.turnOffAuthorisationSystem();
        context.setCurrentUser(admin);
        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        //2. a workspace item
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                                  .withTitle("Workspace Item 1")
                                                  .withIssueDate("2017-10-17")
                                                  .withAuthor("Smith, Donald").withAuthor("Doe, John")
                                                  .withSubject("ExtraEntry")
                                                  .build();

        context.restoreAuthSystemState();

        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(get("/api/submission/workspaceitems/search/item")
                                .param("uuid", String.valueOf(witem.getItem().getID())))
                   .andExpect(status().isForbidden());
    }

    @Test
    public void findByItemUuidUnAuthenticatedTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        //2. a workspace item
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                                  .withTitle("Workspace Item 1")
                                                  .withIssueDate("2017-10-17")
                                                  .withAuthor("Smith, Donald").withAuthor("Doe, John")
                                                  .withSubject("ExtraEntry")
                                                  .build();
        context.restoreAuthSystemState();
        getClient().perform(get("/api/submission/workspaceitems/search/item")
                                     .param("uuid", String.valueOf(witem.getItem().getID())))
                        .andExpect(status().isUnauthorized());
    }

    @Test
    public void patchAddMetadataOnSectionNotExistentTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                         .withName("Parent Community")
                         .build();

        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                          .withName("Sub Community")
                          .build();

        Collection col1 = CollectionBuilder.createCollection(context, child1)
                         .withName("Collection 1")
                         .build();

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                             .withIssueDate("2019-04-25")
                             .withSubject("ExtraEntry")
                             .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);

        List<Operation> addTitle = new ArrayList<Operation>();
        List<Map<String, String>> values = new ArrayList<Map<String, String>>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "New Title");
        values.add(value);
        addTitle.add(new AddOperation("/sections/not-existing-section/dc.title", values));

        String patchBody = getPatchContent(addTitle);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                 .content(patchBody)
                 .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                 .andExpect(status().isUnprocessableEntity());

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                 .andExpect(status().isOk())
                 .andExpect(jsonPath("$.sections.traditionalpageone['dc.title']").doesNotExist());

    }

    @Test
    public void patchAddMetadataWrongAttributeTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                         .withName("Parent Community")
                         .build();

        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                          .withName("Sub Community")
                          .build();

        Collection col1 = CollectionBuilder.createCollection(context, child1)
                         .withName("Collection 1")
                         .build();

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                             .withIssueDate("2019-04-25")
                             .withSubject("ExtraEntry")
                             .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);

        List<Operation> addTitle = new ArrayList<Operation>();
        List<Map<String, String>> values = new ArrayList<Map<String, String>>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "New Title");
        values.add(value);
        addTitle.add(new AddOperation("/sections/traditionalpageone/dc.not.existing", values));

        String patchBody = getPatchContent(addTitle);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                 .content(patchBody)
                 .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                 .andExpect(status().isUnprocessableEntity());
    }

    @Test
    // try to add Title on tradiotionalpagetwo, but attribute title is placed on tradiotionalpageone
    public void patchAddTitleOnSectionThatNotContainAttributeTitleTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                         .withName("Parent Community")
                         .build();

        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                          .withName("Sub Community")
                          .build();

        Collection col1 = CollectionBuilder.createCollection(context, child1)
                         .withName("Collection 1")
                         .build();

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                             .withIssueDate("2019-11-21")
                             .withSubject("ExtraEntry")
                             .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);

        List<Operation> addTitle = new ArrayList<Operation>();
        List<Map<String, String>> values = new ArrayList<Map<String, String>>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "New Title");
        values.add(value);
        addTitle.add(new AddOperation("/sections/traditionalpagetwo/dc.title", values));

        String patchBody = getPatchContent(addTitle);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                 .content(patchBody)
                 .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                 .andExpect(status().isUnprocessableEntity());

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                 .andExpect(status().isOk())
                 .andExpect(jsonPath("$.sections.traditionalpageone['dc.title']").doesNotExist());
    }

    @Test
    /**
     * Test the metadata extraction step adding an identifier
     *
     * @throws Exception
     */
    public void lookupPubmedMetadataTest() throws Exception {
        context.turnOffAuthorisationSystem();
        //** GIVEN **
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1, "123456789/extraction-test")
                .withName("Collection 1").build();
        String authToken = getAuthToken(admin.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .build();
        WorkspaceItem witem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("This is a test title")
                .build();
        context.restoreAuthSystemState();

        // try to add the pmid identifier
        List<Operation> addId = new ArrayList<Operation>();
        // create a list of values to use in add operation
        List<Map<String, String>> values = new ArrayList<Map<String, String>>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "18926410");
        values.add(value);
        addId.add(new AddOperation("/sections/traditionalpageone/dc.identifier.other", values));

        String patchBody = getPatchContent(addId);

        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                    .andExpect(status().isOk())
                    // testing lookup
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.other'][0].value",
                        is("18926410")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                        is("Transfer of peanut allergy from the donor to a lung transplant recipient.")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                        is(Matchers.notNullValue())))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                        is(Matchers.notNullValue())))
                    .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract'][0].value",
                        is(Matchers.notNullValue())))
            ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                // testing lookup
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.other'][0].value",
                    is("18926410")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                    is("Transfer of peanut allergy from the donor to a lung transplant recipient.")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                    is(Matchers.notNullValue())))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                    is(Matchers.notNullValue())))
                .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract'][0].value",
                    is(Matchers.notNullValue())))
            ;

        // verify that adding a pmid to a wsitem with already a title metadata will not alter the user input
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem2.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                    .andExpect(status().isOk())
                    // testing lookup
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.other'][0].value",
                        is("18926410")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                        is("This is a test title")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                        is(Matchers.notNullValue())))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                        is(Matchers.notNullValue())))
                    .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract'][0].value",
                        is(Matchers.notNullValue())))
            ;

        // verify that we can remove metadata provided by pubmed
        List<Operation> removeTitle = new ArrayList<Operation>();
        removeTitle.add(new RemoveOperation("/sections/traditionalpageone/dc.title/0"));
        String rmPatchBody = getPatchContent(removeTitle);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem2.getID())
                .content(rmPatchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                    .andExpect(status().isOk())
                    // testing lookup
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.other'][0].value",
                        is("18926410")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.title']").doesNotExist())
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                        is(Matchers.notNullValue())))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                        is(Matchers.notNullValue())))
                    .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract'][0].value",
                        is(Matchers.notNullValue())))
            ;
        // verify that if we add more values to the listened metadata the lookup is not triggered again
        // (i.e. the title stays empty)
        List<Operation> addId2 = new ArrayList<Operation>();
        addId2.add(new AddOperation("/sections/traditionalpageone/dc.identifier.other/-", value));

        patchBody = getPatchContent(addId2);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem2.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                    .andExpect(status().isOk())
                    // testing lookup
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.other'][0].value",
                        is("18926410")))
                    // second copy of the added identifier
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.other'][1].value",
                            is("18926410")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.title']").doesNotExist())
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                        is(Matchers.notNullValue())))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                        is(Matchers.notNullValue())))
                    .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract'][0].value",
                        is(Matchers.notNullValue())))
            ;

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem2.getID()))
                .andExpect(status().isOk())
                // testing lookup
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.other'][0].value",
                    is("18926410")))
                // second copy of the added identifier
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.identifier.other'][1].value",
                        is("18926410")))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.title']").doesNotExist())
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                    is(Matchers.notNullValue())))
                .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                    is(Matchers.notNullValue())))
                .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract'][0].value",
                    is(Matchers.notNullValue())))
            ;

    }

    @Test
    public void uploadBibtexFileOnExistingSubmissionTest() throws Exception {
        context.turnOffAuthorisationSystem();
        //** GIVEN **
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1, "123456789/extraction-test")
                .withName("Collection 1").build();
        String authToken = getAuthToken(admin.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .build();
        WorkspaceItem witem2 = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("This is a test title")
                .build();
        context.restoreAuthSystemState();


        InputStream bibtex = getClass().getResourceAsStream("bibtex-test.bib");
        final MockMultipartFile bibtexFile = new MockMultipartFile("file", "/local/path/bibtex-test.bib",
            "application/x-bibtex", bibtex);

        try {
            // adding a bibtex file with a single entry should automatically put the metadata in the bibtex file into
            // the item
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems/" + witem.getID())
                        .file(bibtexFile))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                            is("My Article")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                            is("Nobody Jr")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                            is("2006")))
                    .andExpect(jsonPath("$.sections.upload.files[0]"
                         + ".metadata['dc.source'][0].value",
                            is("/local/path/bibtex-test.bib")))
                    .andExpect(jsonPath("$.sections.upload.files[0]"
                         + ".metadata['dc.title'][0].value",
                            is("bibtex-test.bib")));

            // do again over a submission that already has a title, the manual input should be preserved
            getClient(authToken).perform(fileUpload("/api/submission/workspaceitems/" + witem2.getID())
                        .file(bibtexFile))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.title'][0].value",
                            is("This is a test title")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.contributor.author'][0].value",
                            is("Nobody Jr")))
                    .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued'][0].value",
                            is("2006")))
                    .andExpect(jsonPath("$.sections.upload.files[0]"
                         + ".metadata['dc.source'][0].value",
                            is("/local/path/bibtex-test.bib")))
                    .andExpect(jsonPath("$.sections.upload.files[0]"
                         + ".metadata['dc.title'][0].value",
                            is("bibtex-test.bib")));
        } finally {
            bibtex.close();
        }
    }

    @Test
    public void patchAcceptLicenseWrontPathTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                         .withName("Parent Community")
                         .build();

        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                          .withName("Sub Community")
                          .build();

        Collection col1 = CollectionBuilder.createCollection(context, child1)
                         .withName("Collection 1")
                         .build();

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                             .withTitle("Example Title")
                             .withIssueDate("2019-11-21")
                             .withSubject("ExtraEntry")
                             .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);

        List<Operation> replaceGrant = new ArrayList<Operation>();
        replaceGrant.add(new ReplaceOperation("/sections/license/not-existing", true));

        String patchBody = getPatchContent(replaceGrant);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                 .content(patchBody)
                 .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                 .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void patchAcceptLicenseTryToChangeLicenseUrlTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                         .withName("Parent Community")
                         .build();

        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                          .withName("Sub Community")
                          .build();

        Collection col1 = CollectionBuilder.createCollection(context, child1)
                         .withName("Collection 1")
                         .build();

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                             .withTitle("Example Title")
                             .withIssueDate("2019-11-21")
                             .withSubject("ExtraEntry")
                             .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);

        List<Operation> replaceGrant = new ArrayList<Operation>();
        replaceGrant.add(new ReplaceOperation("/sections/license/granted", true));

        String patchBody = getPatchContent(replaceGrant);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                 .content(patchBody)
                 .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                 .andExpect(status().isOk());

        List<Operation> replaceLicenseUrl = new ArrayList<Operation>();
        replaceLicenseUrl.add(new ReplaceOperation("/sections/license/url", "not.existing"));

        patchBody = getPatchContent(replaceLicenseUrl);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                 .content(patchBody)
                 .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                 .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void patchAcceptLicenseTryToChangeDateAccepteLicenseTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                         .withName("Parent Community")
                         .build();

        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                          .withName("Sub Community")
                          .build();

        Collection col1 = CollectionBuilder.createCollection(context, child1)
                         .withName("Collection 1")
                         .build();

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                             .withTitle("Example Title")
                             .withIssueDate("2019-11-21")
                             .withSubject("ExtraEntry")
                             .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);

        List<Operation> replaceGrant = new ArrayList<Operation>();
        replaceGrant.add(new ReplaceOperation("/sections/license/granted", true));

        String patchBody = getPatchContent(replaceGrant);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                 .content(patchBody)
                 .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                 .andExpect(status().isOk());

        List<Operation> replaceLicenseUrl = new ArrayList<Operation>();
        replaceLicenseUrl.add(new ReplaceOperation("/sections/license/acceptanceDate", "2020-02-14"));

        patchBody = getPatchContent(replaceLicenseUrl);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                 .content(patchBody)
                 .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                 .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void patchUploadMetadatoNotExistTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .build();

        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                 .withTitle("Test WorkspaceItem")
                 .withIssueDate("2019-10-25")
                 .withFulltext("simple-article.pdf", "/local/path/simple-article.pdf", pdf)
                 .build();

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);

        List<Operation> addOpts = new ArrayList<Operation>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "some text");
        addOpts.add(new AddOperation("/sections/upload/files/0/metadata/dc.not.existing", value));

        String patchBody = getPatchContent(addOpts);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                            .content(patchBody)
                            .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void patchUploadNotConfiguredMetadataTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .build();

        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem")
                .withFulltext("simple-article.pdf", "/local/path/simple-article.pdf", pdf)
                .build();

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);

        List<Operation> addOpts = new ArrayList<Operation>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "2020-01-25");
        addOpts.add(new AddOperation("/sections/upload/files/0/metadata/dc.date.issued", value));

        String patchBody = getPatchContent(addOpts);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                            .content(patchBody)
                            .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isUnprocessableEntity());

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                 .andExpect(status().isOk())
                 .andExpect(jsonPath("$.sections.bitstream-metadata['dc.date.issued']").doesNotExist())
                 .andExpect(jsonPath("$.sections.traditionalpageone['dc.date.issued']").doesNotExist());
    }

    @Test
    public void patchUploadMissingFieldTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .build();

        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem")
                .withIssueDate("2017-10-17")
                .withFulltext("simple-article.pdf", "/local/path/simple-article.pdf", pdf)
                .build();

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);

        List<Operation> addOpts = new ArrayList<Operation>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "test text");

        addOpts.add(new AddOperation("/sections/upload/files/0/metadata", value));

        String patchBody = getPatchContent(addOpts);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void patchUploadNotExistingPropertyTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .build();

        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem")
                .withIssueDate("2017-10-17")
                .withFulltext("simple-article.pdf", "/local/path/simple-article.pdf", pdf)
                .build();

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);

        List<Operation> addOpts = new ArrayList<Operation>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "test text");

        addOpts.add(new AddOperation("/sections/upload/files/0/not-existing-property/dc.title", value));

        String patchBody = getPatchContent(addOpts);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void patchUploadWithWrongPathTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .build();

        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem")
                .withIssueDate("2017-10-17")
                .withFulltext("simple-article.pdf", "/local/path/simple-article.pdf", pdf)
                .build();

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);

        List<Operation> addOpts = new ArrayList<Operation>();
        Map<String, String> value = new HashMap<String, String>();
        value.put("value", "test text");

        addOpts.add(new AddOperation("/sections/upload/files/0", value));

        String patchBody = getPatchContent(addOpts);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isUnprocessableEntity());

        addOpts.add(new AddOperation("/sections/upload/files", value));
        patchBody = getPatchContent(addOpts);

        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void patchDeleteSectionWithEPersonTest() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .build();
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                             .withTitle("Test WorkspaceItem")
                             .withIssueDate("2020-01-21")
                             .withSubject("Subject 1")
                             .withSubject("Subject 2")
                             .withAbstract("Test description abstract")
                             .build();

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                 .andExpect(status().isOk())
                 .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject']").isNotEmpty())
                 .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract']").isNotEmpty());

        List<Operation> operations = new ArrayList<Operation>();
        operations.add(new RemoveOperation("/sections/traditionalpagetwo"));
        String patchBody = getPatchContent(operations);

        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                 .content(patchBody)
                 .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                 .andExpect(status().isOk())
                 .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject']").doesNotExist())
                 .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract']").doesNotExist());

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                 .andExpect(status().isOk())
                 .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.subject']").doesNotExist())
                 .andExpect(jsonPath("$.sections.traditionalpagetwo['dc.description.abstract']").doesNotExist());
    }

    @Test
    public void patchDeleteMetadataThatNotExistTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                         .withName("Parent Community")
                         .build();

        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                          .withName("Sub Community")
                          .build();

        Collection col1 = CollectionBuilder.createCollection(context, child1)
                         .withName("Collection 1")
                         .build();

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                             .withIssueDate("2020-04-21")
                             .withSubject("ExtraEntry")
                             .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);

        List<Operation> operations = new ArrayList<Operation>();
        operations.add(new RemoveOperation("/sections/traditionalpageone/dc.not.existing/0"));

        String patchBody = getPatchContent(operations);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                 .content(patchBody)
                 .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                 .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void patchDeleteMetadataWrongSectionTest() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                         .withName("Parent Community")
                         .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                          .withName("Sub Community")
                          .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                         .withName("Collection 1")
                         .build();
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                             .withTitle("Test title")
                             .withIssueDate("2019-04-25")
                             .withSubject("ExtraEntry")
                             .build();
        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);
        context.restoreAuthSystemState();

        String authToken = getAuthToken(eperson.getEmail(), password);
        List<Operation> operations = new ArrayList<Operation>();
        operations.add(new RemoveOperation("/sections/traditionalpagetwo/dc.title/0"));
        String patchBody = getPatchContent(operations);

        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                 .content(patchBody)
                 .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                 .andExpect(status().isUnprocessableEntity());

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                 .andExpect(status().isOk())
                 .andExpect(jsonPath("$",
                         Matchers.is(WorkspaceItemMatcher.matchItemWithTitleAndDateIssuedAndSubject(witem,
                                 "Test title", "2019-04-25", "ExtraEntry"))));
    }

    @Test
    public void findOneFullProjectionTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        //2. a workspace item
        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                                  .withTitle("Workspace Item 1")
                                                  .withIssueDate("2017-10-17")
                                                  .withAuthor("Smith, Donald").withAuthor("Doe, John")
                                                  .withSubject("ExtraEntry")
                                                  .build();

        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        String epersonToken = getAuthToken(eperson.getEmail(), password);

        getClient(adminToken).perform(get("/api/submission/workspaceitems/" + witem.getID())
                                .param("projection", "full"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$._embedded.collection._embedded.adminGroup", nullValue()));


        getClient(epersonToken).perform(get("/api/submission/workspaceitems/" + witem.getID())
                                          .param("projection", "full"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$._embedded.collection._embedded.adminGroup").doesNotExist());

    }

    @Test
    public void patchAddMetadataToGroupTypeMetadataTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1, "123456789/traditional-cris")
                                           .withName("Collection 1")
                                           .build();

        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                                  .withTitle("Test witem")
                                                  .withIssueDate("2017-10-17")
                                                  .withSubject("ExtraEntry")
                                                  .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        List<Operation> list = new ArrayList<Operation>();
        List<Map<String, String>> values = new ArrayList<Map<String, String>>();
        Map<String, String> author = new HashMap<String, String>();
        List<Map<String, String>> values2 = new ArrayList<Map<String, String>>();
        Map<String, String> affiliation = new HashMap<String, String>();
        author.put("value", "Mykhaylo Boychuk");
        values.add(author);
        affiliation.put("value", "4science");
        values2.add(affiliation);
        list.add(new AddOperation("/sections/traditionalpageone-cris/dc.contributor.author", values));
        list.add(new AddOperation("/sections/traditionalpageone-cris/person.affiliation.name", values2));

        String patchBody = getPatchContent(list);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                       .andExpect(status().isOk())
                       .andExpect(jsonPath("$.sections.traditionalpageone-cris['dc.contributor.author'][0].value",
                                        is("Mykhaylo Boychuk")))
                       .andExpect(jsonPath("$.sections.traditionalpageone-cris['person.affiliation.name'][0].value",
                                        is("4science")));

        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                 .andExpect(status().isOk())
                 .andExpect(jsonPath("$.errors").doesNotExist())
                 .andExpect(jsonPath("$.sections.traditionalpageone-cris['dc.contributor.author'][0].value",
                                  is("Mykhaylo Boychuk")))
                 .andExpect(jsonPath("$.sections.traditionalpageone-cris['person.affiliation.name'][0].value",
                                  is("4science")));
    }

    @Test
    public void patchAddMetadataToInlineGroupTypeMetadataTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1, "123456789/traditional-cris")
                                           .withName("Collection 1")
                                           .build();

        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                                  .withTitle("Test witem")
                                                  .withIssueDate("2017-10-17")
                                                  .withSubject("ExtraEntry")
                                                  .build();

        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);

        context.restoreAuthSystemState();

        List<Operation> list = new ArrayList<Operation>();
        List<Map<String, String>> values = new ArrayList<Map<String, String>>();
        Map<String, String> idName = new HashMap<String, String>();
        List<Map<String, String>> values2 = new ArrayList<Map<String, String>>();
        Map<String, String> identifier = new HashMap<String, String>();
        idName.put("value", "test name");
        values.add(idName);
        identifier.put("value", "test id");
        values2.add(identifier);
        list.add(new AddOperation("/sections/traditionalpageone-cris/orgunit.identifier.name", values));
        list.add(new AddOperation("/sections/traditionalpageone-cris/orgunit.identifier.id", values2));

        String patchBody = getPatchContent(list);
        getClient(authToken).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                .content(patchBody)
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                       .andExpect(status().isOk())
                       .andExpect(jsonPath("$.sections.traditionalpageone-cris['orgunit.identifier.name'][0].value",
                                        is("test name")))
                       .andExpect(jsonPath("$.sections.traditionalpageone-cris['orgunit.identifier.id'][0].value",
                                        is("test id")));

        // verify that the patch changes have been persisted
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.sections.traditionalpageone-cris['orgunit.identifier.name'][0].value",
                             is("test name")))
            .andExpect(jsonPath("$.sections.traditionalpageone-cris['orgunit.identifier.id'][0].value",
                             is("test id")));
    }

    @Test
    public void createEmptyWorkspaceItemWithEntityTypeTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .withEntityType("Publication")
                .withSubmitterGroup(eperson)
                .build();
        Collection col2 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 2")
                .withEntityType("Journal")
                .withSubmissionDefinition("traditional")
                .withSubmitterGroup(eperson)
                .build();
        context.restoreAuthSystemState();

        AtomicReference<Integer> idRef1 = new AtomicReference<>();
        AtomicReference<Integer> idRef2 = new AtomicReference<>();
        try {

            String authToken = getAuthToken(eperson.getEmail(), password);

            // create a workspaceitem explicitly with entityType Publication
            getClient(authToken).perform(post("/api/submission/workspaceitems")
                    .param("entityType", "Publication")
                    .param("projection", "full")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$._embedded.collection.metadata.['dspace.entity.type'][0].value",
                            equalTo("Publication")))
                    .andDo(result -> idRef1.set(read(result.getResponse().getContentAsString(), "$.id")));


            // create a workspaceitem explicitly with entityType Journal
            getClient(authToken).perform(post("/api/submission/workspaceitems")
                    .param("entityType", "Journal")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$._embedded.collection.metadata.['dspace.entity.type'][0].value",
                            equalTo("Journal")))
                    .andDo(result -> idRef2.set(read(result.getResponse().getContentAsString(), "$.id")));


        } finally {
            WorkspaceItemBuilder.deleteWorkspaceItem(idRef1.get());
            WorkspaceItemBuilder.deleteWorkspaceItem(idRef2.get());
        }
    }

    @Test
    public void createEmptyWorkspateItemWithEntityTypeAndCollectionWithNoMatchTest() throws Exception {
        context.turnOffAuthorisationSystem();
        context.setCurrentUser(eperson);

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .withEntityType("Publication")
                .build();

        context.restoreAuthSystemState();

        try {

            String authToken = getAuthToken(eperson.getEmail(), password);

            // create a workspaceitem explicitly with entityType Publication
            getClient(authToken).perform(post("/api/submission/workspaceitems")
                    .param("entityType", "Journal")
                    .param("owningCollection", col1.getID().toString())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.id").doesNotExist());


        } finally {

        }
    }

    @Test
    public void entityTypeInvalidTest() throws Exception {
        context.turnOffAuthorisationSystem();
        context.setCurrentUser(eperson);

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .withEntityType("Publication")
                .build();

        context.restoreAuthSystemState();


        try {

            String authToken = getAuthToken(eperson.getEmail(), password);


            getClient(authToken).perform(post("/api/submission/workspaceitems")
                    .param("entityType", "NotValidType")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnprocessableEntity());

        } finally {

        }
    }

    @Test
    public void testWorkspaceItemPoliciesWithSharedWorkspace() throws Exception {

        context.turnOffAuthorisationSystem();

        EPerson submitter1 = EPersonBuilder.createEPerson(context)
            .withEmail("submitter1@test.com")
            .withPassword(password)
            .build();

        EPerson submitter2 = EPersonBuilder.createEPerson(context)
            .withEmail("submitter2@test.com")
            .withPassword(password)
            .build();

        parentCommunity = CommunityBuilder.createCommunity(context)
            .withName("Parent Community")
            .build();

        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
            .withName("Collection")
            .withEntityType("Publication")
            .withSubmitterGroup(submitter1, submitter2)
            .withSharedWorkspace()
            .build();

        context.restoreAuthSystemState();

        AtomicReference<Integer> idRef = new AtomicReference<>();
        try {

            getClient(getAuthToken(submitter1.getEmail(), password)).perform(post("/api/submission/workspaceitems")
                .param("owningCollection", col1.getID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._embedded.collection.id", is(col1.getID().toString())))
                .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(), "$.id")));

            WorkspaceItem workspaceItem = workspaceItemService.find(context, idRef.get());
            assertThat(workspaceItem, notNullValue());

            List<ResourcePolicy> policies = authorizeService.getPolicies(context, workspaceItem.getItem());
            assertThat(policies, hasSize(10));

            assertThat(policies, hasItem(matches(Constants.READ, submitter1, TYPE_SUBMISSION)));
            assertThat(policies, hasItem(matches(Constants.WRITE, submitter1, TYPE_SUBMISSION)));
            assertThat(policies, hasItem(matches(Constants.DELETE, submitter1, TYPE_SUBMISSION)));
            assertThat(policies, hasItem(matches(Constants.REMOVE, submitter1, TYPE_SUBMISSION)));
            assertThat(policies, hasItem(matches(Constants.ADD, submitter1, TYPE_SUBMISSION)));
            assertThat(policies, hasItem(matches(Constants.READ, col1.getSubmitters(), TYPE_SUBMISSION)));
            assertThat(policies, hasItem(matches(Constants.WRITE, col1.getSubmitters(), TYPE_SUBMISSION)));
            assertThat(policies, hasItem(matches(Constants.DELETE, col1.getSubmitters(), TYPE_SUBMISSION)));
            assertThat(policies, hasItem(matches(Constants.REMOVE, col1.getSubmitters(), TYPE_SUBMISSION)));
            assertThat(policies, hasItem(matches(Constants.ADD, col1.getSubmitters(), TYPE_SUBMISSION)));

            Operation addOperation = new AddOperation("/sections/publication/dc.contributor.author",
                List.of(Map.of("value", "White, Walter")));

            String patchBody = getPatchContent(List.of(addOperation));
            getClient(getAuthToken(submitter2.getEmail(), password))
                .perform(patch("/api/submission/workspaceitems/" + workspaceItem.getID())
                    .content(patchBody)
                    .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.publication['dc.contributor.author'][0].value", is("White, Walter")));

            getClient(getAuthToken(submitter2.getEmail(), password))
                .perform(delete("/api/submission/workspaceitems/" + workspaceItem.getID()))
                .andExpect(status().isNoContent());

            getClient(getAuthToken(submitter2.getEmail(), password))
                .perform(get("/api/submission/workspaceitems/" + workspaceItem.getID()))
                .andExpect(status().isNotFound());

        } finally {
            WorkspaceItemBuilder.deleteWorkspaceItem(idRef.get());
        }
    }

    @Test
    public void testWorkspaceItemPoliciesWithoutSharedWorkspace() throws Exception {

        context.turnOffAuthorisationSystem();

        EPerson submitter1 = EPersonBuilder.createEPerson(context)
            .withEmail("submitter1@test.com")
            .withPassword(password)
            .build();

        EPerson submitter2 = EPersonBuilder.createEPerson(context)
            .withEmail("submitter2@test.com")
            .withPassword(password)
            .build();

        parentCommunity = CommunityBuilder.createCommunity(context)
            .withName("Parent Community")
            .build();

        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
            .withName("Collection")
            .withEntityType("Publication")
            .withSubmitterGroup(submitter1, submitter2)
            .build();

        context.restoreAuthSystemState();

        AtomicReference<Integer> idRef = new AtomicReference<>();
        try {


            getClient(getAuthToken(submitter1.getEmail(), password)).perform(post("/api/submission/workspaceitems")
                .param("owningCollection", col1.getID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._embedded.collection.id", is(col1.getID().toString())))
                .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(), "$.id")));

            WorkspaceItem workspaceItem = workspaceItemService.find(context, idRef.get());
            assertThat(workspaceItem, notNullValue());

            List<ResourcePolicy> policies = authorizeService.getPolicies(context, workspaceItem.getItem());
            assertThat(policies, hasSize(5));

            assertThat(policies, hasItem(matches(Constants.READ, submitter1, TYPE_SUBMISSION)));
            assertThat(policies, hasItem(matches(Constants.WRITE, submitter1, TYPE_SUBMISSION)));
            assertThat(policies, hasItem(matches(Constants.DELETE, submitter1, TYPE_SUBMISSION)));
            assertThat(policies, hasItem(matches(Constants.REMOVE, submitter1, TYPE_SUBMISSION)));
            assertThat(policies, hasItem(matches(Constants.ADD, submitter1, TYPE_SUBMISSION)));

            Operation addOperation = new AddOperation("/sections/publication/dc.contributor.author",
                List.of(Map.of("value", "White, Walter")));

            String patchBody = getPatchContent(List.of(addOperation));
            getClient(getAuthToken(submitter2.getEmail(), password))
                .perform(patch("/api/submission/workspaceitems/" + workspaceItem.getID())
                    .content(patchBody)
                    .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isForbidden());

            getClient(getAuthToken(submitter2.getEmail(), password))
                .perform(delete("/api/submission/workspaceitems/" + workspaceItem.getID()))
                .andExpect(status().isForbidden());

        } finally {
            WorkspaceItemBuilder.deleteWorkspaceItem(idRef.get());
        }
    }

    @Test
    public void uploadBitstreamWithoutAccessConditions() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context).withName("Com").build();
        Collection collection = CollectionBuilder.createCollection(context, community).withName("Col").build();
        WorkspaceItem wItem = WorkspaceItemBuilder.createWorkspaceItem(context, collection).build();
        context.restoreAuthSystemState();

        // prepare dummy bitstream
        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        final MockMultipartFile pdfFile = new MockMultipartFile("file", "/local/path/simple-article.pdf",
            "application/pdf", pdf);

        // auth
        String authToken = getAuthToken(eperson.getEmail(), password);

        // upload file and verify response
        getClient(authToken)
            .perform(fileUpload("/api/submission/workspaceitems/" + wItem.getID()).file(pdfFile))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions", empty()));

        // verify that access conditions have been persisted
        getClient(authToken)
            .perform(get("/api/submission/workspaceitems/" + wItem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions", empty()));
    }

    @Test
    public void patchBitstreamWithAccessConditionOpenAccess() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context).withName("Com").build();
        Collection collection = CollectionBuilder.createCollection(context, community).withName("Col").build();
        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        WorkspaceItem wItem = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
            .withFulltext("upload.pdf", "/local/path/simple-article.pdf", pdf)
            .build();
        context.restoreAuthSystemState();

        // auth
        String authToken = getAuthToken(eperson.getEmail(), password);

        // perpare patch body
        Map<String, String> value = new HashMap<>();
        value.put("name", "openaccess");
        List<Operation> ops = new ArrayList<>();
        ops.add(new AddOperation("/sections/upload/files/0/accessConditions/-", value));
        String patchBody = getPatchContent(ops);

        // submit patch and verify response
        getClient(authToken)
            .perform(
                patch("/api/submission/workspaceitems/" + wItem.getID())
                    .content(patchBody)
                    .contentType(MediaType.APPLICATION_JSON_PATCH_JSON)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].name", is("openaccess")))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].startDate", nullValue()))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].endDate", nullValue()));

        // verify that access conditions have been persisted
        getClient(authToken)
            .perform(get("/api/submission/workspaceitems/" + wItem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].name", is("openaccess")))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].startDate", nullValue()))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].endDate", nullValue()));
    }

    @Test
    public void patchBitstreamWithAccessConditionLeaseAndValidEndDate() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context).withName("Com").build();
        Collection collection = CollectionBuilder.createCollection(context, community).withName("Col").build();
        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        WorkspaceItem wItem = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
            .withFulltext("upload.pdf", "/local/path/simple-article.pdf", pdf)
            .build();
        context.restoreAuthSystemState();

        // auth
        String authToken = getAuthToken(eperson.getEmail(), password);

        // date
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        Date endDate = new Date();
        String endDateStr = dateFmt.format(endDate);

        // prepare patch body
        Map<String, String> accessCondition = new HashMap<>();
        accessCondition.put("name", "lease");
        accessCondition.put("endDate", endDateStr);
        List<Operation> ops = new ArrayList<>();
        ops.add(new AddOperation("/sections/upload/files/0/accessConditions/-", accessCondition));
        String patchBody = getPatchContent(ops);

        // submit patch and verify response
        getClient(authToken)
            .perform(
                patch("/api/submission/workspaceitems/" + wItem.getID())
                    .content(patchBody)
                    .contentType(MediaType.APPLICATION_JSON_PATCH_JSON)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].name", is("lease")))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].startDate", nullValue()))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].endDate", is(endDateStr)));

        // verify that access conditions have been persisted
        getClient(authToken)
            .perform(get("/api/submission/workspaceitems/" + wItem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].name", is("lease")))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].startDate", nullValue()))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].endDate", is(endDateStr)));
    }

    @Test
    public void patchBitstreamWithAccessConditionLeaseAndInvalidEndDate() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context).withName("Com").build();
        Collection collection = CollectionBuilder.createCollection(context, community).withName("Col").build();
        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        WorkspaceItem wItem = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
            .withFulltext("upload.pdf", "/local/path/simple-article.pdf", pdf)
            .build();
        context.restoreAuthSystemState();

        // auth
        String authToken = getAuthToken(eperson.getEmail(), password);

        // date
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        Date endDate = DateUtils.addDays(
            // lease ends 1 day too late
            DateUtils.addMonths(new Date(), 6), 1
        );
        String endDateStr = dateFmt.format(endDate);

        // prepare patch body
        Map<String, String> accessCondition = new HashMap<>();
        accessCondition.put("name", "lease");
        accessCondition.put("endDate", endDateStr);
        List<Operation> ops = new ArrayList<>();
        ops.add(new AddOperation("/sections/upload/files/0/accessConditions/-", accessCondition));
        String patchBody = getPatchContent(ops);

        // submit patch and verify response
        getClient(authToken)
            .perform(
                patch("/api/submission/workspaceitems/" + wItem.getID())
                    .content(patchBody)
                    .contentType(MediaType.APPLICATION_JSON_PATCH_JSON)
            )
            .andExpect(status().isInternalServerError());

        // verify that access conditions array is still empty
        getClient(authToken)
            .perform(get("/api/submission/workspaceitems/" + wItem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions", empty()));
    }

    @Test
    public void patchBitstreamWithAccessConditionEmbargoAndValidStartDate() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context).withName("Com").build();
        Collection collection = CollectionBuilder.createCollection(context, community).withName("Col").build();
        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        WorkspaceItem wItem = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
            .withFulltext("upload.pdf", "/local/path/simple-article.pdf", pdf)
            .build();
        context.restoreAuthSystemState();

        // auth
        String authToken = getAuthToken(eperson.getEmail(), password);

        // date
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        Date startDate = new Date();
        String startDateStr = dateFmt.format(startDate);

        // prepare patch body
        Map<String, String> accessCondition = new HashMap<>();
        accessCondition.put("name", "embargo");
        accessCondition.put("startDate", startDateStr);
        List<Operation> ops = new ArrayList<>();
        ops.add(new AddOperation("/sections/upload/files/0/accessConditions/-", accessCondition));
        String patchBody = getPatchContent(ops);

        // submit patch and verify response
        getClient(authToken)
            .perform(
                patch("/api/submission/workspaceitems/" + wItem.getID())
                    .content(patchBody)
                    .contentType(MediaType.APPLICATION_JSON_PATCH_JSON)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].name", is("embargo")))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].startDate", is(startDateStr)))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].endDate", nullValue()));

        // verify that access conditions have been persisted
        getClient(authToken)
            .perform(get("/api/submission/workspaceitems/" + wItem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].name", is("embargo")))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].startDate", is(startDateStr)))
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions[0].endDate", nullValue()));
    }

    @Test
    public void patchBitstreamWithAccessConditionEmbargoAndInvalidStartDate() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context).withName("Com").build();
        Collection collection = CollectionBuilder.createCollection(context, community).withName("Col").build();
        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        WorkspaceItem wItem = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
            .withFulltext("upload.pdf", "/local/path/simple-article.pdf", pdf)
            .build();
        context.restoreAuthSystemState();

        // auth
        String authToken = getAuthToken(eperson.getEmail(), password);

        // date
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        Date startDate = DateUtils.addDays(
            // embargo ends 1 day too late
            DateUtils.addMonths(new Date(), 36), 1
        );
        String startDateStr = dateFmt.format(startDate);

        // prepare patch body
        Map<String, String> accessCondition = new HashMap<>();
        accessCondition.put("name", "embargo");
        accessCondition.put("startDate", startDateStr);
        List<Operation> ops = new ArrayList<>();
        ops.add(new AddOperation("/sections/upload/files/0/accessConditions/-", accessCondition));
        String patchBody = getPatchContent(ops);

        // submit patch and verify response
        getClient(authToken)
            .perform(
                patch("/api/submission/workspaceitems/" + wItem.getID())
                    .content(patchBody)
                    .contentType(MediaType.APPLICATION_JSON_PATCH_JSON)
            )
            .andExpect(status().isInternalServerError());

        // verify that access conditions have been persisted
        getClient(authToken)
            .perform(get("/api/submission/workspaceitems/" + wItem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions", empty()));
    }

    @Test
    public void patchBitstreamWithAccessConditionOpenAccessAndStartDate() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context).withName("Com").build();
        Collection collection = CollectionBuilder.createCollection(context, community).withName("Col").build();
        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        WorkspaceItem wItem = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
            .withFulltext("upload.pdf", "/local/path/simple-article.pdf", pdf)
            .build();
        context.restoreAuthSystemState();

        // auth
        String authToken = getAuthToken(eperson.getEmail(), password);

        // date
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        Date startDate = new Date();
        String startDateStr = dateFmt.format(startDate);

        // prepare patch body
        Map<String, String> accessCondition = new HashMap<>();
        accessCondition.put("name", "openaccess");
        accessCondition.put("startDate", startDateStr);
        List<Operation> ops = new ArrayList<>();
        ops.add(new AddOperation("/sections/upload/files/0/accessConditions/-", accessCondition));
        String patchBody = getPatchContent(ops);

        // submit patch and verify response
        getClient(authToken)
            .perform(
                patch("/api/submission/workspaceitems/" + wItem.getID())
                    .content(patchBody)
                    .contentType(MediaType.APPLICATION_JSON_PATCH_JSON)
            )
            .andExpect(status().isInternalServerError());

        // verify that access conditions array is still empty
        getClient(authToken)
            .perform(get("/api/submission/workspaceitems/" + wItem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions", empty()));
    }

    @Test
    public void patchBitstreamWithAccessConditionLeaseMissingDate() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context).withName("Com").build();
        Collection collection = CollectionBuilder.createCollection(context, community).withName("Col").build();
        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        WorkspaceItem wItem = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
            .withFulltext("upload.pdf", "/local/path/simple-article.pdf", pdf)
            .build();
        context.restoreAuthSystemState();

        // auth
        String authToken = getAuthToken(eperson.getEmail(), password);

        // prepare patch body
        Map<String, String> accessCondition = new HashMap<>();
        accessCondition.put("name", "lease");
        List<Operation> ops = new ArrayList<>();
        ops.add(new AddOperation("/sections/upload/files/0/accessConditions/-", accessCondition));
        String patchBody = getPatchContent(ops);

        // submit patch and verify response
        getClient(authToken)
            .perform(
                patch("/api/submission/workspaceitems/" + wItem.getID())
                    .content(patchBody)
                    .contentType(MediaType.APPLICATION_JSON_PATCH_JSON)
            )
            .andExpect(status().isInternalServerError());

        // verify that access conditions array is still empty
        getClient(authToken)
            .perform(get("/api/submission/workspaceitems/" + wItem.getID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections.upload.files[0].accessConditions", empty()));
    }

    public void deleteWorkspaceItemWithMinRelationshipsTest() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community with one collection.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Collection col1 = CollectionBuilder
            .createCollection(context, parentCommunity).withName("Collection 1").build();

        Item author1 = ItemBuilder.createItem(context, col1)
                                  .withTitle("Author1")
                                  .withIssueDate("2017-10-17")
                                  .withAuthor("Smith, Donald")
                                  .withPersonIdentifierLastName("Smith")
                                  .withPersonIdentifierFirstName("Donald")
                                  .withEntityType("Person")
                                  .build();

        Item author2 = ItemBuilder.createItem(context, col1)
                                  .withTitle("Author2")
                                  .withIssueDate("2016-02-13")
                                  .withAuthor("Smith, Maria")
                                  .withEntityType("Person")
                                  .build();

        //2. One workspace item.
        WorkspaceItem workspaceItem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                                                          .withEntityType("Publication")
                                                          .build();

        EntityType publication = EntityTypeBuilder.createEntityTypeBuilder(context, "Publication").build();
        EntityType person = EntityTypeBuilder.createEntityTypeBuilder(context, "Person").build();


        RelationshipType isAuthorOfPublication = RelationshipTypeBuilder
            .createRelationshipTypeBuilder(context, publication, person, "isAuthorOfPublication",
                                           "isPublicationOfAuthor", 2, null, 0,
                                           null).withCopyToLeft(false).withCopyToRight(true).build();

        Relationship relationship1 = RelationshipBuilder
            .createRelationshipBuilder(context, workspaceItem.getItem(), author1, isAuthorOfPublication).build();
        Relationship relationship2 = RelationshipBuilder
            .createRelationshipBuilder(context, workspaceItem.getItem(), author2, isAuthorOfPublication).build();

        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);

        getClient(token).perform(get("/api/core/relationships/" + relationship1.getID()))
                .andExpect(status().is(200));
        getClient(token).perform(delete("/api/core/relationships/" + relationship1.getID()))
                        .andExpect(status().is(400));
        //Both relationships still exist
        getClient(token).perform(get("/api/core/relationships/" + relationship1.getID()))
                .andExpect(status().is(200));
        getClient(token).perform(get("/api/core/relationships/" + relationship2.getID()))
                .andExpect(status().is(200));

        //Delete the workspaceitem
        getClient(token).perform(delete("/api/submission/workspaceitems/" + workspaceItem.getID()))
                        .andExpect(status().is(204));
        //The workspaceitem has been deleted
        getClient(token).perform(get("/api/submission/workspaceitems/" + workspaceItem.getID()))
                        .andExpect(status().is(404));
        //The relationships have been deleted
        getClient(token).perform(get("/api/core/relationships/" + relationship1.getID()))
                .andExpect(status().is(404));
        getClient(token).perform(get("/api/core/relationships/" + relationship2.getID()))
                .andExpect(status().is(404));

    }

    @Test
    public void createWorkspaceItemFromExternalSourceOpenAIRE_Test() throws Exception {
        //We turn off the authorization system in order to create the structure as defined below
        context.turnOffAuthorisationSystem();
        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                                           .withName("Collection 1")
                                           .build();

        context.restoreAuthSystemState();

        Integer workspaceItemId = null;
        try {

        ObjectMapper mapper = new ObjectMapper();
        // You have to be an admin to create an Item from an ExternalDataObject
        String token = getAuthToken(admin.getEmail(), password);
        MvcResult mvcResult = getClient(token).perform(post("/api/submission/workspaceitems?owningCollection="
                                                            + col1.getID().toString())
                                                           .contentType(parseMediaType(TEXT_URI_LIST_VALUE))
                                                           .content("https://localhost:8080/server/api/integration/" +
                                                                    "externalsources/openaire/entryValues/777541"))
                                              .andExpect(status().isCreated()).andReturn();

        String content = mvcResult.getResponse().getContentAsString();
        Map<String,Object> map = mapper.readValue(content, Map.class);
        workspaceItemId = (Integer) map.get("id");
        String itemUuidString = String.valueOf(((Map) ((Map) map.get("_embedded")).get("item")).get("uuid"));

        getClient(token).perform(get("/api/submission/workspaceitems/" + workspaceItemId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$", Matchers.allOf(
                                hasJsonPath("$.id", is(workspaceItemId)),
                                hasJsonPath("$.type", is("workspaceitem")),
                                hasJsonPath("$._embedded.item", Matchers.allOf(
                                hasJsonPath("$.id", is(itemUuidString)),
                                hasJsonPath("$.uuid", is(itemUuidString)),
                                hasJsonPath("$.type", is("item")),
                                hasJsonPath("$.metadata", Matchers.allOf(
                                   MetadataMatcher.matchMetadata("dc.title", "OpenAIRE Advancing Open Scholarship"),
                                   MetadataMatcher.matchMetadata("dc.identifier.other", "777541")
                                )))))
                        ));
        } finally {
            WorkspaceItemBuilder.deleteWorkspaceItem(workspaceItemId);
        }
    }

}
