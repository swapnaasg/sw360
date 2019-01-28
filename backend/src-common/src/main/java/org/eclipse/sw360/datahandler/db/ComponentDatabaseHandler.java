/*
 * Copyright Siemens AG, 2013-2019. Part of the SW360 Portal Project.
 * With modifications by Bosch Software Innovations GmbH, 2016.
 *
 * SPDX-License-Identifier: EPL-1.0
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.sw360.datahandler.db;

import com.google.common.collect.*;

import org.eclipse.sw360.components.summary.SummaryType;
import org.eclipse.sw360.datahandler.common.CommonUtils;
import org.eclipse.sw360.datahandler.common.SW360Constants;
import org.eclipse.sw360.datahandler.common.SW360Utils;
import org.eclipse.sw360.datahandler.couchdb.AttachmentConnector;
import org.eclipse.sw360.datahandler.couchdb.DatabaseConnector;
import org.eclipse.sw360.datahandler.entitlement.ComponentModerator;
import org.eclipse.sw360.datahandler.entitlement.ReleaseModerator;
import org.eclipse.sw360.datahandler.permissions.DocumentPermissions;
import org.eclipse.sw360.datahandler.permissions.PermissionUtils;
import org.eclipse.sw360.datahandler.thrift.*;
import org.eclipse.sw360.datahandler.thrift.attachments.Attachment;
import org.eclipse.sw360.datahandler.thrift.attachments.AttachmentType;
import org.eclipse.sw360.datahandler.thrift.attachments.CheckStatus;
import org.eclipse.sw360.datahandler.thrift.components.*;
import org.eclipse.sw360.datahandler.thrift.moderation.ModerationRequest;
import org.eclipse.sw360.datahandler.thrift.moderation.ModerationService;
import org.eclipse.sw360.datahandler.thrift.projects.Project;
import org.eclipse.sw360.datahandler.thrift.users.RequestedAction;
import org.eclipse.sw360.datahandler.thrift.users.User;
import org.eclipse.sw360.datahandler.thrift.vendors.Vendor;
import org.eclipse.sw360.mail.MailConstants;
import org.eclipse.sw360.mail.MailUtil;

import org.apache.log4j.Logger;
import org.apache.thrift.TException;
import org.ektorp.DocumentOperationResult;
import org.ektorp.http.HttpClient;
import org.jetbrains.annotations.NotNull;

import java.net.MalformedURLException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Sets.newHashSet;
import static org.eclipse.sw360.datahandler.common.CommonUtils.*;
import static org.eclipse.sw360.datahandler.common.Duration.durationOf;
import static org.eclipse.sw360.datahandler.common.SW360Assert.assertNotNull;
import static org.eclipse.sw360.datahandler.common.SW360Assert.fail;
import static org.eclipse.sw360.datahandler.permissions.PermissionUtils.makePermission;
import static org.eclipse.sw360.datahandler.thrift.ThriftUtils.copyFields;
import static org.eclipse.sw360.datahandler.thrift.ThriftValidate.ensureEccInformationIsSet;
import static org.eclipse.sw360.datahandler.thrift.ThriftValidate.prepareComponents;
import static org.eclipse.sw360.datahandler.thrift.ThriftValidate.prepareReleases;

/**
 * Class for accessing Component information from the database
 *
 * @author cedric.bodet@tngtech.com
 * @author Johannes.Najjar@tngtech.com
 * @author alex.borodin@evosoft.com
 * @author thomas.maier@evosoft.com
 */
public class ComponentDatabaseHandler extends AttachmentAwareDatabaseHandler {

    private static final Logger log = Logger.getLogger(ComponentDatabaseHandler.class);
    private static final String ECC_AUTOSET_COMMENT = "automatically set";
    private static final String ECC_AUTOSET_VALUE = "N";

    /**
     * Connection to the couchDB database
     */
    private final ComponentRepository componentRepository;
    private final ReleaseRepository releaseRepository;
    private final VendorRepository vendorRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    private final AttachmentConnector attachmentConnector;
    /**
     * Access to moderation
     */
    private final ComponentModerator moderator;
    private final ReleaseModerator releaseModerator;
    public static final List<EccInformation._Fields> ECC_FIELDS = Arrays.asList(EccInformation._Fields.ECC_STATUS, EccInformation._Fields.AL, EccInformation._Fields.ECCN, EccInformation._Fields.MATERIAL_INDEX_NUMBER, EccInformation._Fields.ECC_COMMENT);

    private final MailUtil mailUtil = new MailUtil();

    public ComponentDatabaseHandler(Supplier<HttpClient> httpClient, String dbName, String attachmentDbName, ComponentModerator moderator, ReleaseModerator releaseModerator) throws MalformedURLException {
        super(httpClient, dbName, attachmentDbName);
        DatabaseConnector db = new DatabaseConnector(httpClient, dbName);

        // Create the repositories
        vendorRepository = new VendorRepository(db);
        releaseRepository = new ReleaseRepository(db, vendorRepository);
        componentRepository = new ComponentRepository(db, releaseRepository, vendorRepository);
        projectRepository = new ProjectRepository(db);
        userRepository = new UserRepository(db);

        // Create the moderator
        this.moderator = moderator;
        this.releaseModerator = releaseModerator;

        // Create the attachment connector
        attachmentConnector = new AttachmentConnector(httpClient, attachmentDbName, durationOf(30, TimeUnit.SECONDS));
    }


    public ComponentDatabaseHandler(Supplier<HttpClient> httpClient, String dbName, String attachmentDbName) throws MalformedURLException {
        this(httpClient, dbName, attachmentDbName, new ComponentModerator(), new ReleaseModerator());
    }

    public ComponentDatabaseHandler(Supplier<HttpClient> httpClient, String dbName, String attachmentDbName, ThriftClients thriftClients) throws MalformedURLException {
        this(httpClient, dbName, attachmentDbName, new ComponentModerator(thriftClients), new ReleaseModerator(thriftClients));
    }

    private void autosetReleaseClearingState(Release releaseAfter, Release releaseBefore) {
        Optional<Attachment> oldBestCR = getBestClearingReport(releaseBefore);
        Optional<Attachment> newBestCR = getBestClearingReport(releaseAfter);
        if (newBestCR.isPresent()){
            if (newBestCR.get().getCheckStatus() == CheckStatus.ACCEPTED){
                releaseAfter.setClearingState(ClearingState.APPROVED);
            }else{
                releaseAfter.setClearingState(ClearingState.REPORT_AVAILABLE);
            }
        } else {
            if (oldBestCR.isPresent()) releaseAfter.setClearingState(ClearingState.NEW_CLEARING);
        }
    }

    /////////////////////
    // SUMMARY GETTERS //
    /////////////////////
    public List<Component> getComponentsShort(Set<String> ids) {
        return componentRepository.makeSummary(SummaryType.SHORT, ids);
    }

    public List<Component> getComponentSummary(User user) {
        return componentRepository.getComponentSummary(user);
    }

    public List<Component> getComponentSummaryForExport() {
        return componentRepository.getSummaryForExport();
    }

    public List<Component> getComponentDetailedSummaryForExport() {
        return componentRepository.getDetailedSummaryForExport();
    }

    public List<Release> getReleaseSummary() throws TException {
        List<Release> releases = releaseRepository.getReleaseSummary();
        releases.forEach(ThriftValidate::ensureEccInformationIsSet);


        // todo: move filling out of department to ReleaseRepository/ReleaseSummary???
        Set<String> userIds = releases.stream().map(Release::getCreatedBy).collect(Collectors.toSet());
        Map<String, User> usersByEmail = ThriftUtils.getIdMap(userRepository.get(userIds));
        releases.forEach(release -> release.setCreatorDepartment(Optional
                .ofNullable(release.getCreatedBy())
                .map(usersByEmail::get)
                .map(User::getDepartment)
                .orElse(null)));
        return releases;
    }

    public List<Release> getRecentReleases() {
        return releaseRepository.getRecentReleases();
    }

    public List<Component> getSubscribedComponents(String user) {
        return componentRepository.getSubscribedComponents(user);
    }

    public List<Release> getSubscribedReleases(String email) {
        return releaseRepository.getSubscribedReleases(email);
    }


    public List<Release> getReleasesFromVendorId(String id, User user) throws TException {
        return releaseRepository.getReleasesFromVendorId(id, user);
    }

    public List<Release> getReleasesFromVendorIds(Set<String> ids) {
        return releaseRepository.getReleasesFromVendorIds(ids);
    }

    public List<Release> getReleasesFromComponentId(String id, User user) throws TException {
        return releaseRepository.getReleasesFromComponentId(id, user);
    }

    public List<Component> getMyComponents(String user) {
        Collection<Component> myComponents = componentRepository.getMyComponents(user);

        return componentRepository.makeSummaryFromFullDocs(SummaryType.HOME, myComponents);
    }

    public List<Component> getSummaryForExport() {
        return componentRepository.getSummaryForExport();
    }

    ////////////////////////////
    // GET INDIVIDUAL OBJECTS //
    ////////////////////////////

    public Component getComponent(String id, User user) throws SW360Exception {
        Component component = componentRepository.get(id);

        if (component == null) {
            throw fail("Could not fetch component from database! id=" + id);
        }

        // Convert Ids to release summary
        component.setReleases(releaseRepository.makeSummaryWithPermissions(SummaryType.SUMMARY, component.releaseIds, user));
        component.unsetReleaseIds();

        setMainLicenses(component);

        // Set permissions
        makePermission(component, user).fillPermissions();

        return component;
    }

    public Release getRelease(String id, User user) throws SW360Exception {
        Release release = releaseRepository.get(id);

        if (release == null) {
            throw fail("Could not fetch release from database! id=" + id);
        }

        vendorRepository.fillVendor(release);
        // Set permissions
        if (user != null) {
            makePermission(release, user).fillPermissions();
        }

        ensureEccInformationIsSet(release);

        return release;
    }

    private void setMainLicenses(Component component) {
        if (!component.isSetMainLicenseIds() && component.isSetReleases()) {
            Set<String> licenseIds = new HashSet<>();

            for (Release release : component.getReleases()) {
                licenseIds.addAll(nullToEmptySet(release.getMainLicenseIds()));
            }

            component.setMainLicenseIds(licenseIds);
        }
    }

    ////////////////////////////
    // ADD INDIVIDUAL OBJECTS //
    ////////////////////////////

    /**
     * Add new release to the database
     */
    public AddDocumentRequestSummary addComponent(Component component, String user) throws SW360Exception {
        if(isDuplicate(component)) {
            return new AddDocumentRequestSummary()
                    .setRequestStatus(AddDocumentRequestStatus.DUPLICATE);
        }
        // Prepare the component
        prepareComponent(component);

        // Save creating user
        component.setCreatedBy(user);
        component.setCreatedOn(SW360Utils.getCreatedOn());

        // Add the component to the database and return ID
        componentRepository.add(component);
        sendMailNotificationsForNewComponent(component, user);
        return new AddDocumentRequestSummary()
                .setRequestStatus(AddDocumentRequestStatus.SUCCESS)
                .setId(component.getId());
    }

    /**
     * Add a single new release to the database
     */
    public AddDocumentRequestSummary addRelease(Release release, String user) throws SW360Exception {
        // Prepare the release and get underlying component ID
        prepareRelease(release);
        if(isDuplicate(release)) {
            return new AddDocumentRequestSummary()
                    .setRequestStatus(AddDocumentRequestStatus.DUPLICATE);
        }

        String componentId = release.getComponentId();
        // Ensure that component exists
        Component component = componentRepository.get(componentId);
        assertNotNull(component);

        // Save creating user
        release.setCreatedBy(user);
        release.setCreatedOn(SW360Utils.getCreatedOn());

        // Add default ECC options if download url is set
        autosetEccFieldsForReleaseWithDownloadUrl(release);

        // Add release to database
        releaseRepository.add(release);
        final String id = release.getId();

        // Update the underlying component
        component.addToReleaseIds(id);

        if (!component.isSetLanguages()) {
            component.setLanguages(new HashSet<String>());
        }
        if (!component.isSetOperatingSystems()) {
            component.setOperatingSystems(new HashSet<String>());
        }
        if (!component.isSetVendorNames()) {
            component.setVendorNames(new HashSet<String>());
        }
        if (!component.isSetMainLicenseIds()) {
            component.setMainLicenseIds(new HashSet<String>());
        }

        updateReleaseDependentFieldsForComponent(component, release);
        componentRepository.update(component);

        sendMailNotificationsForNewRelease(release, user);
        return new AddDocumentRequestSummary()
                .setRequestStatus(AddDocumentRequestStatus.SUCCESS)
                .setId(id);
    }

    private boolean isDuplicate(Component component){
        Set<String> duplicates = componentRepository.getComponentIdsByName(component.getName());
        return duplicates.size()>0;
    }

    private boolean isDuplicate(Release release){
        List<Release> duplicates = releaseRepository.searchByNameAndVersion(release.getName(), release.getVersion());
        return duplicates.size()>0;
    }

    private void resetReleaseDependentFields(Component component) {
        component.setLanguages(new HashSet<String>());
        component.setOperatingSystems(new HashSet<String>());
        component.setVendorNames(new HashSet<String>());
        component.setMainLicenseIds(new HashSet<String>());
    }

    public void updateReleaseDependentFieldsForComponent(Component component, Release release) {
        if (release != null && component != null) {
            if (!component.isSetLanguages()) {
                component.setLanguages(new HashSet<String>());
            }
            component.languages.addAll(nullToEmptySet(release.languages));

            if (!component.isSetOperatingSystems()) {
                component.setOperatingSystems(new HashSet<String>());
            }
            component.operatingSystems.addAll(nullToEmptySet(release.operatingSystems));

            if (!component.isSetVendorNames()) {
                component.setVendorNames(new HashSet<String>());
            }
            if (release.vendor != null)
                component.vendorNames.add(release.vendor.getShortname());
            else if (!isNullOrEmpty(release.vendorId)) {
                Vendor vendor = getVendor(release.vendorId);
                component.vendorNames.add(vendor.getShortname());
            }

            if (!component.isSetMainLicenseIds()) component.setMainLicenseIds(new HashSet<String>());
            if (release.isSetMainLicenseIds()) {
                component.getMainLicenseIds().addAll(release.getMainLicenseIds());
            }
        }
    }

    private Vendor getVendor(String vendorId) {
        return vendorRepository.get(vendorId);
    }

    ///////////////////////////////
    // UPDATE INDIVIDUAL OBJECTS //
    ///////////////////////////////

    public RequestStatus updateComponent(Component component, User user) throws SW360Exception {
        // Prepare component for database
        prepareComponent(component);

        // Get actual document for members that should not change
        Component actual = componentRepository.get(component.getId());
        assertNotNull(actual, "Could not find component to update!");

        if (changeWouldResultInDuplicate(actual, component)) {
            return RequestStatus.DUPLICATE;
        } else if (makePermission(actual, user).isActionAllowed(RequestedAction.WRITE)) {
            // Nested releases and attachments should not be updated by this method
            if (actual.isSetReleaseIds()) {
                component.setReleaseIds(actual.getReleaseIds());
            }

            copyFields(actual, component, ThriftUtils.IMMUTABLE_OF_COMPONENT);
            component.setAttachments(getAllAttachmentsToKeep(toSource(actual), actual.getAttachments(), component.getAttachments()));
            updateComponentInternal(component, actual, user);

        } else {
            return moderator.updateComponent(component, user);
        }
        return RequestStatus.SUCCESS;

    }

    private boolean changeWouldResultInDuplicate(Component before, Component after) {
        if (before.getName().equals(after.getName())) {
            // sth else was changed, not one of the duplication relevant properties
            return false;
        }

        return isDuplicate(after);
    }

    private void updateComponentInternal(Component updated, Component current, User user) {
        // Update the database with the component
        componentRepository.update(updated);

        //clean up attachments in database
        attachmentConnector.deleteAttachmentDifference(current.getAttachments(), updated.getAttachments());
        sendMailNotificationsForComponentUpdate(updated, user.getEmail());
    }

    private void prepareComponent(Component component) throws SW360Exception {
        // Prepare component for database
        ThriftValidate.prepareComponent(component);

        //add sha1 to attachments if necessary
        if(component.isSetAttachments()) {
            attachmentConnector.setSha1ForAttachments(component.getAttachments());
        }
    }

    public RequestSummary updateComponents(Set<Component> components, User user) throws SW360Exception {
        return RepositoryUtils.doBulk(prepareComponents(components), user, componentRepository);
    }


    public RequestStatus updateComponentFromAdditionsAndDeletions(Component componentAdditions, Component componentDeletions, User user){

        try {
            Component component= getComponent(componentAdditions.getId(), user);
            component = moderator.updateComponentFromModerationRequest(component, componentAdditions, componentDeletions);
            return updateComponent(component, user);
        } catch (SW360Exception e) {
            log.error("Could not get original component when updating from moderation request.");
            return RequestStatus.FAILURE;
        }
    }


    public RequestStatus mergeComponents(String mergeTargetId, String mergeSourceId, Component mergeSelection,
            User sessionUser) throws TException {
        Component mergeTarget = getComponent(mergeTargetId, sessionUser);
        Component mergeSource = getComponent(mergeSourceId, sessionUser);
        // load releases anew and overwrite them in the component, because getComponent() returns release summaries
        // without revision, but we need the revision to update releases in mergeReleases(), or else couchdb reports
        // revision conflict
        Set<String> sourceReleaseIds = nullToEmptyList(mergeSource.getReleases()).stream().map(Release::getId).collect(Collectors.toSet());
        mergeSource.setReleases(getReleasesForClearingStateSummary(sourceReleaseIds));

        if (!makePermission(mergeTarget, sessionUser).isActionAllowed(RequestedAction.WRITE)
                || !makePermission(mergeSource, sessionUser).isActionAllowed(RequestedAction.WRITE)
                || !makePermission(mergeSource, sessionUser).isActionAllowed(RequestedAction.DELETE)) {
            return RequestStatus.FAILURE;
        }

        if (isComponentUnderModeration(mergeTargetId) ||
                isComponentUnderModeration(mergeSourceId)){
            return RequestStatus.IN_USE;
        }

        mergePlainFields(mergeSelection, mergeTarget);
        mergeReleases(mergeSource, mergeTarget, mergeSelection, sessionUser);
        mergeAttachments(mergeSelection, mergeTarget, mergeSource);

        // first, update source before deletion so that attachments and releases and
        // stuff that has been migrated will not be deleted by component deletion!
        updateComponentCompletely(mergeSource, sessionUser);
        updateComponentCompletely(mergeTarget, sessionUser);
        deleteComponent(mergeSourceId, sessionUser);

        return RequestStatus.SUCCESS;
    }

    private boolean isComponentUnderModeration(String componentSourceId) throws TException {
        ModerationService.Iface moderationClient = new ThriftClients().makeModerationClient();
        List<ModerationRequest> sourceModerationRequests = moderationClient.getModerationRequestByDocumentId(componentSourceId);
        return sourceModerationRequests.stream().anyMatch(CommonUtils::isInProgressOrPending);
    }

    private void mergePlainFields(Component mergeSelection, Component mergeTarget) {
        copyFields(mergeSelection, mergeTarget, ImmutableSet.<Component._Fields>builder()
                .add(Component._Fields.NAME)
                .add(Component._Fields.CREATED_ON)
                .add(Component._Fields.CREATED_BY)
                .add(Component._Fields.CATEGORIES)
                .add(Component._Fields.COMPONENT_TYPE)
                .add(Component._Fields.HOMEPAGE)
                .add(Component._Fields.BLOG)
                .add(Component._Fields.WIKI)
                .add(Component._Fields.MAILINGLIST)
                .add(Component._Fields.DESCRIPTION)
                .add(Component._Fields.COMPONENT_OWNER)
                .add(Component._Fields.OWNER_ACCOUNTING_UNIT)
                .add(Component._Fields.OWNER_GROUP)
                .add(Component._Fields.MODERATORS)
                .add(Component._Fields.SUBSCRIBERS)
                .add(Component._Fields.ROLES)
                .build());
    }

    private void mergeAttachments(Component mergeSelection, Component mergeTarget, Component mergeSource) {
        // --- handle attachments (a bit more complicated)
        // prepare for no NPE
        if (mergeSource.getAttachments() == null) {
            mergeSource.setAttachments(new HashSet<>());
        }
        if (mergeTarget.getAttachments() == null) {
            mergeTarget.setAttachments(new HashSet<>());
        }

        Set<String> attachmentIdsSelected = mergeSelection.getAttachments().stream()
                .map(Attachment::getAttachmentContentId).collect(Collectors.toSet());
        // add new attachments from source
        Set<Attachment> attachmentsToAdd = new HashSet<>();
        mergeSource.getAttachments().forEach(a -> {
            if (attachmentIdsSelected.contains(a.getAttachmentContentId())) {
                attachmentsToAdd.add(a);
            }
        });
        // remove moved attachments in source
        attachmentsToAdd.forEach(a -> {
            mergeTarget.addToAttachments(a);
            mergeSource.getAttachments().remove(a);
        });
        // delete unchosen attachments from target
        Set<Attachment> attachmentsToDelete = new HashSet<>();
        mergeTarget.getAttachments().forEach(a -> {
            if (!attachmentIdsSelected.contains(a.getAttachmentContentId())) {
                attachmentsToDelete.add(a);
            }
        });
        mergeTarget.getAttachments().removeAll(attachmentsToDelete);

    }

    private void mergeReleases(Component mergeSource, Component mergeTarget, Component mergeSelection, User sessionUser) throws SW360Exception {
        // --- handle releases (a bit more complicated)

        Set<String> selectedReleaseIds = nullToEmptyList(mergeSelection.getReleases()).stream().map(Release::getId).collect(Collectors.toSet());

        // Migrate selected releases from source to target
        List<Release> sourceReleases = nullToEmptyList(mergeSource.getReleases());
        sourceReleases.stream()
                .filter(r -> selectedReleaseIds.contains(r.getId()))
                .forEach(r -> {
                    r.setComponentId(mergeTarget.getId());
                    // overwrite the release name with the name of the new target component, but only if it is equal
                    // to the name of the source component. Example: if we're merging component 'android' into 'Android',
                    // we don't want to override the release name 'Lollipop' with 'Android'. In contrast, when merging
                    // e.g. Apache Commons into Commons, we do want to overwrite release name.
                    if (Objects.equals(r.getName(), mergeSource.getName())) {
                        r.setName(mergeSelection.getName());
                    }
                });
        updateReleases(sourceReleases, sessionUser);

        // remove releaseids from source so that they don't get deleted on deletion of
        // source component later on (releases are not part of the component in couchdb,
        // only the ids)
        mergeSource.setReleaseIds(new HashSet<>());

        // only release ids are persisted, the list of release objects are joined so
        // there is no need to update that one
        mergeTarget.setReleaseIds(new HashSet<>());
        selectedReleaseIds.forEach(mergeTarget::addToReleaseIds);
    }

    /**
     * The {{@link #updateComponent(Component, User)} does not change the given
     * component completely according to the user request. As we want to have
     * exactly the given component as a result, this method is really submitting the
     * given data to the persistence.
     */
    private void updateComponentCompletely(Component component, User user) throws SW360Exception {
        // Prepare component for database
        prepareComponent(component);

        Component actual = componentRepository.get(component.getId());
        assertNotNull(actual, "Could not find component to update!");

        updateComponentInternal(component, actual, user);

    }

    public RequestStatus updateRelease(Release release, User user, Iterable<Release._Fields> immutableFields) throws SW360Exception {
        // Prepare release for database
        prepareRelease(release);

        // Get actual document for members that should no change
        Release actual = releaseRepository.get(release.getId());
        assertNotNull(actual, "Could not find release to update");

        if (actual.equals(release)) {
            return RequestStatus.SUCCESS;
        } else if (changeWouldResultInDuplicate(actual, release)) {
            return RequestStatus.DUPLICATE;
        } else {
            DocumentPermissions<Release> permissions = makePermission(actual, user);
            boolean hasChangesInEccFields = hasChangesInEccFields(release, actual);

            if ((hasChangesInEccFields && permissions.isActionAllowed(RequestedAction.WRITE_ECC))
                    || (!hasChangesInEccFields && permissions.isActionAllowed(RequestedAction.WRITE))) {

                if (!hasChangesInEccFields && hasEmptyEccFields(release)) {
                    autosetEccFieldsForReleaseWithDownloadUrl(release);
                }

                copyFields(actual, release, immutableFields);

                autosetReleaseClearingState(release, actual);
                if (hasChangesInEccFields) {
                    autosetEccUpdaterInfo(release, user);
                }
                release.setAttachments(
                        getAllAttachmentsToKeep(toSource(actual), actual.getAttachments(), release.getAttachments()));
                deleteAttachmentUsagesOfUnlinkedReleases(release, actual);
                releaseRepository.update(release);
                updateReleaseDependentFieldsForComponentId(release.getComponentId());
                // clean up attachments in database
                attachmentConnector.deleteAttachmentDifference(nullToEmptySet(actual.getAttachments()),
                        nullToEmptySet(release.getAttachments()));
                sendMailNotificationsForReleaseUpdate(release, user.getEmail());
            } else {
                if (hasChangesInEccFields) {
                    return releaseModerator.updateReleaseEccInfo(release, user);
                } else {
                    return releaseModerator.updateRelease(release, user);
                }
            }

            return RequestStatus.SUCCESS;
        }
    }

    private boolean changeWouldResultInDuplicate(Release before, Release after) {
        if (before.getName().equals(after.getName()) && ((before.getVersion() == null && after.getVersion() == null)
                || (before.getVersion() != null && before.getVersion().equals(after.getVersion())))) {
            // sth else was changed, not one of the duplication relevant properties
            return false;
        }

        return isDuplicate(after);
    }

    private void deleteAttachmentUsagesOfUnlinkedReleases(Release updated, Release actual) throws SW360Exception {
        Source usedBy = Source.releaseId(updated.getId());
        Set<String> updatedLinkedReleaseIds = nullToEmptyMap(updated.getReleaseIdToRelationship()).keySet();
        Set<String> actualLinkedReleaseIds = nullToEmptyMap(actual.getReleaseIdToRelationship()).keySet();
        deleteAttachmentUsagesOfUnlinkedReleases(usedBy, updatedLinkedReleaseIds, actualLinkedReleaseIds);
    }

    public boolean hasChangesInEccFields(Release release, Release actual) {
        ensureEccInformationIsSet(release);
        ensureEccInformationIsSet(actual);
        Function<EccInformation._Fields, Boolean> fieldChanged = f -> {
            Object changedValue = release.getEccInformation().getFieldValue(f);
            Object originalValue = actual.getEccInformation().getFieldValue(f);

            return !((changedValue == originalValue)
                    || (changedValue != null && changedValue.equals(originalValue))
                    || ("".equals(changedValue) && originalValue == null)
                    || (changedValue == null && "".equals(originalValue)));
        };
        return ECC_FIELDS
                .stream().map(fieldChanged)
                .reduce(false, Boolean::logicalOr);
    }

    public boolean hasEmptyEccFields(Release release) {
        EccInformation eccInformation = release.getEccInformation();
        return isNullEmptyOrWhitespace(eccInformation.getAL()) &&
                isNullEmptyOrWhitespace(eccInformation.getECCN()) &&
                isNullEmptyOrWhitespace(eccInformation.getEccComment()) &&
                (eccInformation.getEccStatus() == null || eccInformation.getEccStatus() == ECCStatus.OPEN);
    }

    private void autosetEccUpdaterInfo(Release release, User user) {
        ensureEccInformationIsSet(release);
        release.getEccInformation().setAssessmentDate(SW360Utils.getCreatedOn());
        release.getEccInformation().setAssessorContactPerson(user.getEmail());
        release.getEccInformation().setAssessorDepartment(user.getDepartment());
    }

    private void autosetEccFieldsForReleaseWithDownloadUrl(Release release) {
        // For unmodified OSS, ECC classification can be done automatically
        // This release has to be an OSS component and should have a valid Url address
        Component parentComponent = componentRepository.get(release.getComponentId());
        ComponentType compType = parentComponent.getComponentType();

        String url = release.getDownloadurl();
        if (!isNullOrEmpty(url) && ComponentType.OSS.equals(compType)) {
            if (CommonUtils.isValidUrl(url)) {
                ensureEccInformationIsSet(release);
                EccInformation eccInfo = release.getEccInformation();
                eccInfo.setAL(ECC_AUTOSET_VALUE);
                eccInfo.setECCN(ECC_AUTOSET_VALUE);
                eccInfo.setEccComment(ECC_AUTOSET_COMMENT);
                eccInfo.setEccStatus(ECCStatus.APPROVED);
                eccInfo.setAssessmentDate(SW360Utils.getCreatedOn());
            } else {
                log.warn("Could not set ECC options for unmodified OSS because download url is not valid: " + url);
            }
        }
    }

    private void prepareRelease(Release release) throws SW360Exception {
        // Prepare release for database
        ThriftValidate.prepareRelease(release);

        //add sha1 to attachments if necessary
        if(release.isSetAttachments()) {
            attachmentConnector.setSha1ForAttachments(release.getAttachments());
        }
    }

    public RequestSummary updateReleases(Collection<Release> releases, User user) throws SW360Exception {
        List<Release> storedReleases = prepareReleases(releases);

        RequestSummary requestSummary = new RequestSummary();
        if (PermissionUtils.isAdmin(user)) {
            // Prepare component for database
            final List<DocumentOperationResult> documentOperationResults = componentRepository.executeBulk(storedReleases);

            if (documentOperationResults.isEmpty()) {

                final List<Component> componentList = componentRepository.get(storedReleases
                        .stream()
                        .map(Release::getComponentId)
                        .collect(Collectors.toSet()));

                final Map<String, Component> componentsById = ThriftUtils.getIdMap(componentList);

                for (Release storedRelease : storedReleases) {
                    final Component component = componentsById.get(storedRelease.getComponentId());
                    component.addToReleaseIds(storedRelease.getId());
                    updateReleaseDependentFieldsForComponent(component, storedRelease);
                }

                updateComponents(newHashSet(componentList), user);
            }

            requestSummary.setTotalElements(storedReleases.size());
            requestSummary.setTotalAffectedElements(storedReleases.size() - documentOperationResults.size());

            requestSummary.setRequestStatus(RequestStatus.SUCCESS);
        } else {
            requestSummary.setRequestStatus(RequestStatus.FAILURE);
        }
        return requestSummary;
    }

    public RequestStatus updateReleaseFromAdditionsAndDeletions(Release releaseAdditions, Release releaseDeletions, User user){

        try {
            Release release = getRelease(releaseAdditions.getId(), user);
            release = releaseModerator.updateReleaseFromModerationRequest(release, releaseAdditions, releaseDeletions);
            return updateRelease(release, user, ThriftUtils.IMMUTABLE_OF_RELEASE);
        } catch (SW360Exception e) {
            log.error("Could not get original release when updating from moderation request.");
            return RequestStatus.FAILURE;
        }

    }

    protected Component updateReleaseDependentFieldsForComponentId(String componentId) {
        Component component = componentRepository.get(componentId);
        recomputeReleaseDependentFields(component, null);
        componentRepository.update(component);

        return component;
    }

    public void recomputeReleaseDependentFields(Component component, String skipThisReleaseId) {
        resetReleaseDependentFields(component);

        List<Release> releases = releaseRepository.get(component.getReleaseIds());
        for (Release containedRelease : releases) {
            if (containedRelease.getId().equals(skipThisReleaseId)) continue;
            updateReleaseDependentFieldsForComponent(component, containedRelease);
        }
    }

    ///////////////////////////////
    // DELETE INDIVIDUAL OBJECTS //
    ///////////////////////////////


    public RequestStatus deleteComponent(String id, User user) throws SW360Exception {
        Component component = componentRepository.get(id);
        assertNotNull(component);

        final Set<String> releaseIds = component.getReleaseIds();
        if (releaseIds!=null && releaseIds.size()>0) return RequestStatus.IN_USE;
        if (checkIfInUse(releaseIds)) return RequestStatus.IN_USE;


        if (makePermission(component, user).isActionAllowed(RequestedAction.DELETE)) {


            for (Release release : releaseRepository.get(nullToEmptySet(component.releaseIds))) {
                component = removeReleaseAndCleanUp(release);
            }

            // Remove the component with attachments
            attachmentConnector.deleteAttachments(component.getAttachments());
            attachmentDatabaseHandler.deleteUsagesBy(Source.componentId(id));
            componentRepository.remove(component);
            moderator.notifyModeratorOnDelete(id);
            return RequestStatus.SUCCESS;
        } else {
            return moderator.deleteComponent(component, user);
        }
    }

    public boolean checkIfInUseComponent(String componentId) {
        Component component = componentRepository.get(componentId);
        return checkIfInUse(component);
    }

    public boolean checkIfInUse(Component component) {
        return checkIfInUse(component.getReleaseIds());
    }

    public boolean checkIfInUse(Set<String> releaseIds) {
        if (releaseIds != null && releaseIds.size() > 0) {
            final Set<Component> usingComponents = componentRepository.getUsingComponents(releaseIds);
            if (usingComponents.size() > 0)
                return true;

            final Set<Project> usingProjects = projectRepository.searchByReleaseId(releaseIds);
            if (usingProjects.size() > 0)
                return true;
        }
        return false;
    }

    public boolean checkIfInUse(String releaseId) {

        final Set<Component> usingComponents = componentRepository.getUsingComponents(releaseId);
        if (usingComponents.size() > 0)
            return true;

        final Set<Project> usingProjects = projectRepository.searchByReleaseId(releaseId);
        return (usingProjects.size() > 0);
    }

    private Component removeReleaseAndCleanUp(Release release) throws SW360Exception {
        attachmentConnector.deleteAttachments(release.getAttachments());
        attachmentDatabaseHandler.deleteUsagesBy(Source.releaseId(release.getId()));

        Component component = updateReleaseDependentFieldsForComponentId(release.getComponentId());

        //TODO notify using projects!?? Or stop if there are any

        moderator.notifyModeratorOnDelete(release.getId());
        releaseRepository.remove(release);

        return component;
    }

    public RequestStatus deleteRelease(String id, User user) throws SW360Exception {
        Release release = releaseRepository.get(id);
        assertNotNull(release);

        if (checkIfInUse(id)) return RequestStatus.IN_USE;

        if (makePermission(release, user).isActionAllowed(RequestedAction.DELETE)) {
            // Remove release id from component
            removeReleaseId(id, release.componentId);
            removeReleaseAndCleanUp(release);
            return RequestStatus.SUCCESS;
        } else {
            return releaseModerator.deleteRelease(release, user);
        }
    }

    private void removeReleaseId(String releaseId, String componentId) throws SW360Exception {
        // Remove release id from component
        Component component = componentRepository.get(componentId);
        assertNotNull(component);
        recomputeReleaseDependentFields(component, releaseId);
        component.getReleaseIds().remove(releaseId);
        componentRepository.update(component);
    }

    /////////////////////
    // HELPER SERVICES //
    /////////////////////

    List<ReleaseLink> getLinkedReleases(Project project, Map<String, Release> releaseMap, Deque<String> visitedIds) {
        return getLinkedReleases(project.getReleaseIdToUsage(), releaseMap, visitedIds);
    }

    private List<ReleaseLink> getLinkedReleases(Map<String, ?> relations, Map<String, Release> releaseMap, Deque<String> visitedIds) {
        return iterateReleaseRelationShips(relations, null, visitedIds, releaseMap);
    }

    public List<ReleaseLink> getLinkedReleases(Map<String, ?> relations) {
        final Map<String, Release> releaseMap = ThriftUtils.getIdMap(getDetailedReleasesForExport(relations.keySet()));
        return getLinkedReleases(relations, releaseMap, new ArrayDeque<>());
    }

    public List<Release> getAllReleases() {
        return releaseRepository.getAll();
    }

    public Map<String, Release> getAllReleasesIdMap() {
        final List<Release> releases = getAllReleases();
        return ThriftUtils.getIdMap(releases);
    }

    @NotNull
    private List<ReleaseLink> iterateReleaseRelationShips(Map<String, ?> relations, String parentNodeId, Deque<String> visitedIds, Map<String, Release> releaseMap) {
        List<ReleaseLink> out = new ArrayList<>();

        for (Map.Entry<String, ?> entry : relations.entrySet()) {
            String id = entry.getKey();
            Optional<ReleaseLink> releaseLinkOptional = getFilledReleaseLink(id, entry.getValue(), parentNodeId, visitedIds, releaseMap);
            releaseLinkOptional.ifPresent(out::add);
        }
        out.sort(SW360Utils.RELEASE_LINK_COMPARATOR);
        return out;
    }

    private Optional<ReleaseLink> getFilledReleaseLink(String id, Object relation, String parentNodeId, Deque<String> visitedIds, Map<String, Release> releaseMap) {
        ReleaseLink releaseLink = null;
        if (!visitedIds.contains(id)) {
            visitedIds.push(id);
            Release release = releaseMap.get(id);
            if (release != null) {
                releaseLink = createReleaseLink(release);
                fillValueFieldInReleaseLink(releaseLink, relation);
                releaseLink.setNodeId(generateNodeId(id));
                releaseLink.setParentNodeId(parentNodeId);
                if (release.isSetMainLicenseIds()) {
                    releaseLink.setLicenseIds(release.getMainLicenseIds());
                }
            } else {
                log.error("Broken ReleaseLink in release with id: " + parentNodeId + ". Linked release with id " + id + " was not in the release cache");
            }
            visitedIds.pop();
        }
        return Optional.ofNullable(releaseLink);
    }


    private void fillValueFieldInReleaseLink(ReleaseLink releaseLink, Object relation) {
        if (relation instanceof ProjectReleaseRelationship) {
            ProjectReleaseRelationship rel = (ProjectReleaseRelationship) relation;
            releaseLink.setReleaseRelationship(rel.getReleaseRelation());
            releaseLink.setMainlineState(rel.getMainlineState());
        } else if (relation instanceof ReleaseRelationship) {
            releaseLink.setReleaseRelationship((ReleaseRelationship) relation);
        } else {
            throw new IllegalArgumentException("Only ProjectReleaseRelationship or ReleaseRelationship is allowed as ReleaseLink's relation value");
        }
    }

    @NotNull
    private ReleaseLink createReleaseLink(Release release) {
        vendorRepository.fillVendor(release);
        String vendorName = release.isSetVendor() ? release.getVendor().getShortname() : "";
        ReleaseLink releaseLink = new ReleaseLink(release.id, vendorName, release.name, release.version, SW360Utils.printFullname(release),
                 !nullToEmptyMap(release.getReleaseIdToRelationship()).isEmpty());
        releaseLink
                .setClearingState(release.getClearingState())
                .setComponentType(
                        Optional.ofNullable(componentRepository.get(release.getComponentId()))
                                .map(Component::getComponentType)
                                .orElse(null));
        if (!nullToEmptySet(release.getAttachments()).isEmpty()) {
            releaseLink.setAttachments(Lists.newArrayList(release.getAttachments()));
        }
        return releaseLink;
    }

    private String generateNodeId(String id) {
        return id == null ? null : id + "_" + UUID.randomUUID();
    }

    public List<Release> searchReleaseByNamePrefix(String name) {
        return releaseRepository.searchByNamePrefix(name);
    }

    public List<Release> getReleases(Set<String> ids) {
        return releaseRepository.makeSummary(SummaryType.SHORT, ids);
    }

    public Set<Component> searchComponentsByExternalIds(Map<String, Set<String>> externalIds) {
        return componentRepository.searchByExternalIds(externalIds);
    }

    public Set<Release> searchReleasesByExternalIds(Map<String, Set<String>> externalIds) {
        return releaseRepository.searchByExternalIds(externalIds);
    }

    /**
     * Returns full documents straight from repository. Don't want this to get abused, that's why it's package-private.
     * Used for bulk-computing ReleaseClearingStateSummaries by ProjectDatabaseHandler.
     * The reason for this hack is that making summaries (like in getReleases()) takes way too long for a lot of
     * releases.
     */
    List<Release> getReleasesForClearingStateSummary(Set<String> ids) {
        return releaseRepository.get(ids);
    }

    public List<Release> getDetailedReleasesForExport(Set<String> ids) {
        return releaseRepository.makeSummary(SummaryType.DETAILED_EXPORT_SUMMARY, ids);
    }

    public List<Release> getFullReleases(Set<String> ids) {
        return releaseRepository.makeSummary(SummaryType.SUMMARY, ids);
    }

    public List<Release> getReleasesWithPermissions(Set<String> ids, User user) {
        return releaseRepository.makeSummaryWithPermissions(SummaryType.SUMMARY, ids, user);
    }

    public RequestStatus subscribeComponent(String id, User user) throws SW360Exception {
        Component component = componentRepository.get(id);
        assertNotNull(component);

        component.addToSubscribers(user.getEmail());
        componentRepository.update(component);
        return RequestStatus.SUCCESS;
    }

    public RequestStatus subscribeRelease(String id, User user) throws SW360Exception {
        Release release = releaseRepository.get(id);
        assertNotNull(release);

        release.addToSubscribers(user.getEmail());
        releaseRepository.update(release);
        return RequestStatus.SUCCESS;
    }


    public RequestStatus unsubscribeComponent(String id, User user) throws SW360Exception {
        Component component = componentRepository.get(id);
        assertNotNull(component);

        Set<String> subscribers = component.getSubscribers();
        String email = user.getEmail();
        if (subscribers != null && email != null) {
            subscribers.remove(email);
            component.setSubscribers(subscribers);
        }

        componentRepository.update(component);
        return RequestStatus.SUCCESS;
    }

    public RequestStatus unsubscribeRelease(String id, User user) throws SW360Exception {
        Release release = releaseRepository.get(id);
        assertNotNull(release);

        Set<String> subscribers = release.getSubscribers();
        String email = user.getEmail();
        if (subscribers != null && email != null) {
            subscribers.remove(email);
            release.setSubscribers(subscribers);
        }
        releaseRepository.update(release);
        return RequestStatus.SUCCESS;
    }

    public Component getComponentForEdit(String id, User user) throws SW360Exception {
        List<ModerationRequest> moderationRequestsForDocumentId = moderator.getModerationRequestsForDocumentId(id);

        Component component = getComponent(id, user);
        DocumentState documentState;

        if (moderationRequestsForDocumentId.isEmpty()) {
            documentState = CommonUtils.getOriginalDocumentState();
        } else {
            final String email = user.getEmail();
            Optional<ModerationRequest> moderationRequestOptional = CommonUtils.getFirstModerationRequestOfUser(moderationRequestsForDocumentId, email);
            if (moderationRequestOptional.isPresent()
                    && isInProgressOrPending(moderationRequestOptional.get())){
                ModerationRequest moderationRequest = moderationRequestOptional.get();

                component = moderator.updateComponentFromModerationRequest(
                        component,
                        moderationRequest.getComponentAdditions(),
                        moderationRequest.getComponentDeletions());
                documentState = CommonUtils.getModeratedDocumentState(moderationRequest);
            } else {
                documentState = new DocumentState().setIsOriginalDocument(true).setModerationState(moderationRequestsForDocumentId.get(0).getModerationState());
            }
        }
        component.setPermissions(makePermission(component, user).getPermissionMap());
        component.setDocumentState(documentState);
        return component;
    }

    public Release getReleaseForEdit(String id, User user) throws SW360Exception {
        List<ModerationRequest> moderationRequestsForDocumentId = moderator.getModerationRequestsForDocumentId(id);

        Release release = getRelease(id, user);
        DocumentState documentState;

        if (moderationRequestsForDocumentId.isEmpty()) {
            documentState = CommonUtils.getOriginalDocumentState();
        } else {
            final String email = user.getEmail();
            Optional<ModerationRequest> moderationRequestOptional = CommonUtils.getFirstModerationRequestOfUser(moderationRequestsForDocumentId, email);
            if (moderationRequestOptional.isPresent()
                    && isInProgressOrPending(moderationRequestOptional.get())){
                ModerationRequest moderationRequest = moderationRequestOptional.get();

                release = releaseModerator.updateReleaseFromModerationRequest(
                        release,
                        moderationRequest.getReleaseAdditions(),
                        moderationRequest.getReleaseDeletions());
                documentState = CommonUtils.getModeratedDocumentState(moderationRequest);
            } else {
                documentState = new DocumentState().setIsOriginalDocument(true).setModerationState(moderationRequestsForDocumentId.get(0).getModerationState());
            }
        }
        vendorRepository.fillVendor(release);
        release.setPermissions(makePermission(release, user).getPermissionMap());
        release.setDocumentState(documentState);
        ensureEccInformationIsSet(release);
        return release;
    }

    public List<Component> searchComponentByNameForExport(String name) {
        return componentRepository.searchByNameForExport(name);
    }


    public Set<Component> getUsingComponents(String releaseId) {
        return componentRepository.getUsingComponents(releaseId);
    }

    public Set<Component> getUsingComponents(Set<String> releaseIds) {
        return componentRepository.getUsingComponents(releaseIds);
    }

    public Component getComponentForReportFromFossologyUploadId(String uploadId) {

        Component component = componentRepository.getComponentFromFossologyUploadId(uploadId);

        if (component != null) {
            if (component.isSetReleaseIds()) {
                // Convert Ids to release summary
                final Set<String> releaseIds = component.getReleaseIds();
                final List<Release> releases = nullToEmptyList(releaseRepository.get(releaseIds));
                for (Release release : releases) {
                    vendorRepository.fillVendor(release);
                }
                component.setReleases(releases);
                component.unsetReleaseIds();

                setMainLicenses(component);
            }
        }
        return component;
    }

    public Set<String> getusedAttachmentContentIds() {
        return componentRepository.getUsedAttachmentContents();
    }

    public Map<String, List<String>> getDuplicateComponents() {
        ListMultimap<String, String> componentIdentifierToComponentId = ArrayListMultimap.create();

        for (Component component : componentRepository.getAll()) {
            componentIdentifierToComponentId.put(SW360Utils.printName(component), component.getId());
        }
        return CommonUtils.getIdentifierToListOfDuplicates(componentIdentifierToComponentId);
    }

    public Map<String, List<String>> getDuplicateReleases() {
        ListMultimap<String, String> releaseIdentifierToReleaseId = ArrayListMultimap.create();

        for (Release release : getAllReleases()) {
            releaseIdentifierToReleaseId.put(SW360Utils.printName(release), release.getId());
        }

        return CommonUtils.getIdentifierToListOfDuplicates(releaseIdentifierToReleaseId);
    }

    public Set<Attachment> getSourceAttachments(String releaseId) throws SW360Exception {
        Release release = assertNotNull(releaseRepository.get(releaseId));

        return nullToEmptySet(release.getAttachments())
                .stream()
                .filter(Objects::nonNull)
                .filter(input -> input.getAttachmentType() == AttachmentType.SOURCE)
                .collect(Collectors.toSet());
    }

    public Map<String,List<String>> getDuplicateReleaseSources() {
        ListMultimap<String, String> releaseIdentifierToReleaseId = ArrayListMultimap.create();

        for (Release release : getAllReleases()) {

            if(release.isSetAttachments()) {
                for (Attachment attachment : release.getAttachments()) {
                    if (attachment.getAttachmentType() == AttachmentType.SOURCE)
                        releaseIdentifierToReleaseId.put(SW360Utils.printName(release), release.getId());
                }
            }
        }

        return CommonUtils.getIdentifierToListOfDuplicates(releaseIdentifierToReleaseId);
    }

    public List<Component> getRecentComponentsSummary(int limit, User user) {
        return componentRepository.getRecentComponentsSummary(limit, user);
    }

    public int getTotalComponentsCount() {
        return componentRepository.getDocumentCount();
    }

    private void sendMailNotificationsForNewComponent(Component component, String user) {
        mailUtil.sendMail(component.getComponentOwner(),
                MailConstants.SUBJECT_FOR_NEW_COMPONENT,
                MailConstants.TEXT_FOR_NEW_COMPONENT,
                SW360Constants.NOTIFICATION_CLASS_COMPONENT, Component._Fields.COMPONENT_OWNER.toString(),
                component.getName());
        mailUtil.sendMail(component.getModerators(), user,
                MailConstants.SUBJECT_FOR_NEW_COMPONENT,
                MailConstants.TEXT_FOR_NEW_COMPONENT,
                SW360Constants.NOTIFICATION_CLASS_COMPONENT, Component._Fields.MODERATORS.toString(),
                component.getName());
        mailUtil.sendMail(component.getSubscribers(), user,
                MailConstants.SUBJECT_FOR_NEW_COMPONENT,
                MailConstants.TEXT_FOR_NEW_COMPONENT,
                SW360Constants.NOTIFICATION_CLASS_COMPONENT, Component._Fields.SUBSCRIBERS.toString(),
                component.getName());
        mailUtil.sendMail(SW360Utils.unionValues(component.getRoles()), user,
                MailConstants.SUBJECT_FOR_NEW_COMPONENT,
                MailConstants.TEXT_FOR_NEW_COMPONENT,
                SW360Constants.NOTIFICATION_CLASS_COMPONENT, Component._Fields.ROLES.toString(),
                component.getName());
    }

    private void sendMailNotificationsForComponentUpdate(Component component, String user) {
        mailUtil.sendMail(component.getCreatedBy(),
                MailConstants.SUBJECT_FOR_UPDATE_COMPONENT,
                MailConstants.TEXT_FOR_UPDATE_COMPONENT,
                SW360Constants.NOTIFICATION_CLASS_COMPONENT, Component._Fields.CREATED_BY.toString(),
                component.getName());
        mailUtil.sendMail(component.getComponentOwner(),
                MailConstants.SUBJECT_FOR_UPDATE_COMPONENT,
                MailConstants.TEXT_FOR_UPDATE_COMPONENT,
                SW360Constants.NOTIFICATION_CLASS_COMPONENT, Component._Fields.COMPONENT_OWNER.toString(),
                component.getName());
        mailUtil.sendMail(component.getModerators(), user,
                MailConstants.SUBJECT_FOR_UPDATE_COMPONENT,
                MailConstants.TEXT_FOR_UPDATE_COMPONENT,
                SW360Constants.NOTIFICATION_CLASS_COMPONENT, Component._Fields.MODERATORS.toString(),
                component.getName());
        mailUtil.sendMail(component.getSubscribers(), user,
                MailConstants.SUBJECT_FOR_UPDATE_COMPONENT,
                MailConstants.TEXT_FOR_UPDATE_COMPONENT,
                SW360Constants.NOTIFICATION_CLASS_COMPONENT, Component._Fields.SUBSCRIBERS.toString(),
                component.getName());
        mailUtil.sendMail(SW360Utils.unionValues(component.getRoles()), user,
                MailConstants.SUBJECT_FOR_UPDATE_COMPONENT,
                MailConstants.TEXT_FOR_UPDATE_COMPONENT,
                SW360Constants.NOTIFICATION_CLASS_COMPONENT, Component._Fields.ROLES.toString(),
                component.getName());
    }

    private void sendMailNotificationsForNewRelease(Release release, String user) {
        mailUtil.sendMail(release.getContributors(), user,
                MailConstants.SUBJECT_FOR_NEW_RELEASE,
                MailConstants.TEXT_FOR_NEW_RELEASE,
                SW360Constants.NOTIFICATION_CLASS_RELEASE, Release._Fields.CONTRIBUTORS.toString(),
                release.getName(), release.getVersion());
        mailUtil.sendMail(release.getModerators(), user,
                MailConstants.SUBJECT_FOR_NEW_RELEASE,
                MailConstants.TEXT_FOR_NEW_RELEASE,
                SW360Constants.NOTIFICATION_CLASS_RELEASE, Release._Fields.MODERATORS.toString(),
                release.getName(), release.getVersion());
        mailUtil.sendMail(release.getSubscribers(), user,
                MailConstants.SUBJECT_FOR_NEW_RELEASE,
                MailConstants.TEXT_FOR_NEW_RELEASE,
                SW360Constants.NOTIFICATION_CLASS_RELEASE, Release._Fields.SUBSCRIBERS.toString(),
                release.getName(), release.getVersion());
        mailUtil.sendMail(SW360Utils.unionValues(release.getRoles()), user,
                MailConstants.SUBJECT_FOR_NEW_RELEASE,
                MailConstants.TEXT_FOR_NEW_RELEASE,
                SW360Constants.NOTIFICATION_CLASS_RELEASE, Release._Fields.SUBSCRIBERS.toString(),
                release.getName(), release.getVersion());
    }

    private void sendMailNotificationsForReleaseUpdate(Release release, String user) {
        mailUtil.sendMail(release.getCreatedBy(),
                MailConstants.SUBJECT_FOR_UPDATE_RELEASE,
                MailConstants.TEXT_FOR_UPDATE_RELEASE,
                SW360Constants.NOTIFICATION_CLASS_RELEASE, Release._Fields.CONTRIBUTORS.toString(),
                release.getName(), release.getVersion());
        mailUtil.sendMail(release.getContributors(), user,
                MailConstants.SUBJECT_FOR_UPDATE_RELEASE,
                MailConstants.TEXT_FOR_UPDATE_RELEASE,
                SW360Constants.NOTIFICATION_CLASS_RELEASE, Release._Fields.CONTRIBUTORS.toString(),
                release.getName(), release.getVersion());
        mailUtil.sendMail(release.getModerators(), user,
                MailConstants.SUBJECT_FOR_UPDATE_RELEASE,
                MailConstants.TEXT_FOR_UPDATE_RELEASE,
                SW360Constants.NOTIFICATION_CLASS_RELEASE, Release._Fields.MODERATORS.toString(),
                release.getName(), release.getVersion());
        mailUtil.sendMail(release.getSubscribers(), user,
                MailConstants.SUBJECT_FOR_UPDATE_RELEASE,
                MailConstants.TEXT_FOR_UPDATE_RELEASE,
                SW360Constants.NOTIFICATION_CLASS_RELEASE, Release._Fields.SUBSCRIBERS.toString(),
                release.getName(), release.getVersion());
        mailUtil.sendMail(SW360Utils.unionValues(release.getRoles()), user,
                MailConstants.SUBJECT_FOR_UPDATE_RELEASE,
                MailConstants.TEXT_FOR_UPDATE_RELEASE,
                SW360Constants.NOTIFICATION_CLASS_RELEASE, Release._Fields.SUBSCRIBERS.toString(),
                release.getName(), release.getVersion());
    }

}
