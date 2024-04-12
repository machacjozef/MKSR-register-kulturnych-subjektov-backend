package com.netgrif.mksr.migration

import com.netgrif.application.engine.auth.service.interfaces.IUserService
import com.netgrif.application.engine.elastic.service.interfaces.IElasticCaseMappingService
import com.netgrif.application.engine.elastic.service.interfaces.IElasticCaseService
import com.netgrif.application.engine.elastic.service.interfaces.IElasticTaskMappingService
import com.netgrif.application.engine.elastic.service.interfaces.IElasticTaskService
import com.netgrif.application.engine.importer.service.Importer
import com.netgrif.application.engine.petrinet.domain.I18nString
import com.netgrif.application.engine.petrinet.domain.PetriNet
import com.netgrif.application.engine.petrinet.domain.Transition
import com.netgrif.application.engine.petrinet.domain.UriContentType
import com.netgrif.application.engine.petrinet.domain.dataset.Field
import com.netgrif.application.engine.petrinet.domain.dataset.FieldType
import com.netgrif.application.engine.petrinet.domain.dataset.UserField
import com.netgrif.application.engine.petrinet.domain.dataset.UserListField
import com.netgrif.application.engine.petrinet.domain.dataset.logic.action.Action
import com.netgrif.application.engine.petrinet.domain.events.Event
import com.netgrif.application.engine.petrinet.domain.events.EventType
import com.netgrif.application.engine.petrinet.domain.events.ProcessEventType
import com.netgrif.application.engine.petrinet.domain.repositories.PetriNetRepository
import com.netgrif.application.engine.petrinet.domain.roles.ProcessRole
import com.netgrif.application.engine.petrinet.domain.roles.ProcessRoleRepository
import com.netgrif.application.engine.petrinet.service.interfaces.IPetriNetService
import com.netgrif.application.engine.petrinet.service.interfaces.IProcessRoleService
import com.netgrif.application.engine.petrinet.service.interfaces.IUriService
import com.netgrif.application.engine.workflow.domain.*
import com.netgrif.application.engine.workflow.domain.repositories.CaseRepository
import com.netgrif.application.engine.workflow.domain.repositories.TaskRepository
import com.netgrif.application.engine.workflow.service.interfaces.IEventService
import com.netgrif.application.engine.workflow.service.interfaces.ITaskService
import com.netgrif.application.engine.workflow.service.interfaces.IWorkflowService
import com.querydsl.core.types.Predicate
import org.bson.types.ObjectId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component

import java.nio.file.Path
import java.text.Collator
import java.time.LocalDateTime
import java.util.stream.Collectors

import static com.netgrif.mksr.startup.NetRunner.*

@Component
class MigrationHelper {

    public static final Logger log = LoggerFactory.getLogger(MigrationHelper.class)

    @Autowired
    private CaseRepository caseRepository

    @Autowired
    private TaskRepository taskRepository

    @Autowired
    private IPetriNetService service

    @Autowired
    private Importer importer

    @Autowired
    private IProcessRoleService processRoleService

    @Autowired
    private ProcessRoleRepository roleRepository

    @Autowired
    private PetriNetRepository netRepository

    @Autowired
    private ITaskService taskService

    @Autowired
    private IElasticCaseService elasticCaseService

    @Autowired
    private IElasticTaskService elasticTaskService

    @Autowired
    private IElasticCaseMappingService caseMappingService

    @Autowired
    private IElasticTaskMappingService taskMappingService

    @Autowired
    private IUserService userService

    @Autowired
    private MongoTemplate mongoTemplate

    @Autowired
    private IUriService uriService

    @Autowired
    private IEventService eventService

    @Autowired
    private FileStorageConfiguration fileStorageConfiguration

    @Autowired
    private IWorkflowService workflowService

    /**
     * update net identifier and uriNodeId
     * @param currentIdentifier
     * @param newIdentifier
     * @param newPath
     */
    void updateNetIdAndPath(String currentIdentifier, String newIdentifier, String newPath) {
        PetriNet currentNet = service.getNewestVersionByIdentifier(currentIdentifier)
        if (!currentNet) {
            log.warn("$currentIdentifier net not found")
            return
        }
        currentNet.identifier = newIdentifier
        currentNet.uriNodeId = uriService.getOrCreate(newPath, UriContentType.PROCESS).id
        service.save(currentNet).get()
    }

    /**
     * migrate cases to new uri node
     * @param currentIdentifier
     * @param newIdentifier
     * @param newNodeId
     */
    void updateCaseIdentifiers(String currentIdentifier, String newIdentifier, String newNodeId) {
        updateCases({ Case useCase ->
            useCase.processIdentifier = newIdentifier
            useCase.setUriNodeId(newNodeId)
        }, QCase.case$.processIdentifier.eq(currentIdentifier))
    }

    /**
     * Updates all cases filtered by filter Predicate. Update closure is called on each filtered case.
     * @param update
     * @param filter
     */
    void updateCases(Closure update, Predicate filter, boolean catchExceptions = false) {
        iterateCases(update, false, catchExceptions, { Page<Case> cases -> caseRepository.saveAll(cases) }, filter)
    }

    /**
     * Iterates all cases filtered by filter Predicate. Update closure is called on each filtered case. PageProcessed closure is called after each page iteration.
     * @param update
     * @param filter
     */
    void iterateCases(Closure update, boolean casesAreBeingDeleted = false, boolean catchExceptions = false, Closure pageProcessed = {}, long sleepFor = 0, Predicate filter) {
        long caseCount = caseRepository.count(filter)
        long numOfPages = ((caseCount / 100.0) + 1) as long
        log.info("Processing cases: $numOfPages pages")
        numOfPages.times { page ->
            int currentPage = casesAreBeingDeleted ? 0 : page
            log.info("Page $page / $numOfPages")

            Page<Case> cases = caseRepository.findAll(filter, PageRequest.of(currentPage, 100))
            cases.each {
                try {
                    update(it)
                } catch (Exception e) {
                    if (catchExceptions) {
                        log.error("Failed for $it.stringId $it.title) $e.message", e)
                    } else {
                        throw e
                    }
                }
            }
            pageProcessed(cases)
            if (sleepFor != 0) {
                sleep(sleepFor)
            }
        }
    }

    /**
     * Update all cases of a process
     * @param update
     * @param processIdentifier
     */
    void updateCasesCursor(Closure update, String processIdentifier, ObjectId processId = null, boolean catchExceptions = true, double pageSize = 100.0) {
        long caseCount
        if (processId) {
            caseCount = caseRepository.count(QCase.case$.petriNetObjectId.eq(processId))
        } else {
            caseCount = caseRepository.count(QCase.case$.processIdentifier.eq(processIdentifier))
        }
        long numOfPages = ((caseCount / pageSize) + 1) as long
        log.info("Migrating process $processIdentifier $processId")
        log.info("Page size: $pageSize")
        log.info("Processing cases: $numOfPages pages")
        log.info("Total cases: $caseCount")
        ObjectId lastId = null
        if (caseCount > 0) {
            for (int p = 0; p < numOfPages; p++) {
                try {
                    log.info("Page " + (p + 1) + " / $numOfPages")

                    Query query = new Query()
                    if (lastId == null) {
                        query.skip(0)
                    } else {
                        query.addCriteria(Criteria.where("_id").gt(lastId))
                    }
                    query.addCriteria(Criteria.where("processIdentifier").is(processIdentifier))
                    if (processId) {
                        query.addCriteria(Criteria.where("petriNetObjectId").is(processId))
                    }
                    query.limit(pageSize as Integer)

                    List<Case> cases = mongoTemplate.find(query, Case.class)
                    cases.each {
                        try {
                            update(it)
                        } catch (Exception e) {
                            if (catchExceptions) {
                                log.error("Failed for $it.stringId $it.title) $e.message", e)
                            } else {
                                throw e
                            }
                        }
                    }
                    cases = caseRepository.saveAll(cases)

                    lastId = cases.get(cases.size() - 1).get_id()
                } catch (ArrayIndexOutOfBoundsException e) {
                    log.error("Failed to iterate page " + (p + 1))
                    break
                }
            }
        }
    }

    /**
     * Updates all tasks filtered by filter Predicate. Update closure is called on each filtered task.
     * @param update
     * @param filter
     */
    void updateTasks(Closure update, Predicate filter) {
        long taskCount = taskRepository.count(filter)
        long numOfPages = ((taskCount / 100.0) + 1) as long
        log.info("Processing tasks: $numOfPages pages")
        numOfPages.times { page ->
            log.info("Page $page / $numOfPages")

            Page<Task> tasks = taskRepository.findAll(filter, PageRequest.of(page, 100))
            tasks.each { update(it) }

            taskRepository.saveAll(tasks)
        }
    }

    /**
     * Updates existing Petri Net model with new values. New process roles are ignored! New roles in existing user type fields will be ignored!
     * @param fileName
     * @param customUpdates
     */
    void updateNetIgnoreRoles(PetriNetEnum petriNet, List<Closure<PetriNet>> customUpdates = null) {
        updateNetIgnoreRoles(petriNet.identifier, petriNet.file, customUpdates)
    }

    /**
     * Updates existing Petri Net model of specific version with new values. New process roles are ignored! New roles in existing user type fields will be ignored!
     * @param petriNet
     * @param customUpdates
     */
    void updateNetIgnoreRoles(PetriNetEnum petriNet, PetriNet currentNet, List<Closure<PetriNet>> customUpdates = null) {
        File file = new File("src/main/resources/petriNets/" + petriNet.file)
        PetriNet reimported = importer.importPetriNet(file).get()
        assert (reimported.identifier == currentNet.identifier)
        updateNetIgnoreRoles(currentNet, reimported, file, customUpdates)
    }

    void updateNetIgnoreRoles(String identifier, String fileName, List<Closure<PetriNet>> customUpdates = null) {
        PetriNet currentNet = service.getNewestVersionByIdentifier(identifier)
        File file = new File("src/main/resources/petriNets/" + fileName)
        PetriNet reimported = importer.importPetriNet(file).get()
        updateNetIgnoreRoles(currentNet, reimported, file, customUpdates)
    }

    void updateNetIgnoreRoles(PetriNet currentNet, PetriNet reimported, File newFile, List<Closure<PetriNet>> customUpdates) {
        if (!currentNet) {
            log.warn("Net $reimported.identifier does not exist")
            return
        }
        log.info("Migrating net $currentNet.identifier")
        Map<String, ProcessRole> oldUnionRoles = currentNet.roles
        Map<String, ProcessRole> newUnionRoles = reimported.roles

        reimported = replaceUserFieldsRoleReferences(currentNet, reimported)

        ProcessRole defaultRole = processRoleService.defaultRole()
        ProcessRole anonRole = processRoleService.anonymousRole()

        currentNet.title = reimported.title
        currentNet.initials = reimported.initials
        currentNet.defaultCaseName = reimported.defaultCaseName
        currentNet.icon = reimported.icon
        currentNet.places = reimported.places
        currentNet.transitions = reimported.transitions
        currentNet.arcs = reimported.arcs
        currentNet.dataSet = reimported.dataSet
        currentNet.transactions = reimported.transactions
        currentNet.importId = reimported.importId
        currentNet.caseEvents = reimported.caseEvents
        currentNet.processEvents = reimported.processEvents
        currentNet.negativeViewRoles = reimported.negativeViewRoles
        currentNet.userRefs = reimported.userRefs
        currentNet.functions = reimported.functions
        currentNet.identifier = reimported.identifier

        oldUnionRoles.each { oldRoleId, oldRole ->
            ProcessRole newRole = newUnionRoles.values().find { it.importId == oldRole.importId }
            if (newRole) {
                log.info("Updating role $oldRole.name events")
                oldRole.events = newRole.events
            }
        }

        def newPermissions = [:]
        reimported.permissions.each { id, permissions ->
            def newRole = newUnionRoles[id]

            if (!newRole && (defaultRole.stringId == id || anonRole.stringId == id)) {
                log.info("Default/anon role $id on process $currentNet.identifier detected, skipping")
                newPermissions[id] = permissions

            } else {
                def oldRole = oldUnionRoles.values().find {
                    it.importId == newRole.importId
                }

                if (!oldRole) {
                    log.warn("Old role does not exist for role $newRole.importId")
                    return
                }
                newPermissions[oldRole.stringId] = permissions
            }
        }
        currentNet.permissions = newPermissions as Map<String, Map<String, Boolean>>

        currentNet.transitions.each { id, t ->
            Map<String, Map<String, Boolean>> oldRoles = new HashMap<>()
            t.roles.each { roleMongoId, permissions ->
                def newRole = newUnionRoles[roleMongoId]

                if (!newRole && (defaultRole.stringId == roleMongoId || anonRole.stringId == roleMongoId)) {
                    log.info("Default/anonym role $roleMongoId on transition ${t.importId} detected, skipping")
                    oldRoles[roleMongoId] = permissions

                } else {
                    def oldRole = oldUnionRoles.values().find {
                        it.importId == newRole.importId
                    }

                    if (!oldRole) {
                        log.warn("Old role does not exist for role $newRole.importId")
                        return
                    }
                    oldRoles[oldRole.stringId] = permissions
                }
            }
            t.roles = oldRoles
        }
        resolveDataOrder(currentNet)

        customUpdates && customUpdates.each { Closure<PetriNet> customUpdate ->
            currentNet = customUpdate(currentNet, reimported)
        }

        processRoleService.saveAll(oldUnionRoles.values())
        service.save(currentNet)

        runUploadMenuEvents(currentNet)
        log.info("Migrated $currentNet.identifier")

        if (currentNet) {
            FileInputStream fis
            try {
                fis = new FileInputStream(newFile)
                updateNetStoragePath(currentNet, fis)
                log.info("Updated $currentNet.identifier file")

            } catch (Exception e) {
                log.info("Error while updating net file reference [$currentNet.identifier]", e)
                if (fis) {
                    fis.close()
                }
            }
        }
    }

    void runUploadMenuEvents(PetriNet updatedNet) {
        if (updatedNet.processEvents?.containsKey(ProcessEventType.UPLOAD)) {
            List<Action> actions = updatedNet.processEvents[ProcessEventType.UPLOAD].find { it.importId == "menu_import" }?.getPostActions()
            if (actions) {
                eventService.runActions(actions, null, Optional.empty())
            }
        }
    }

    void updateNetStoragePath(PetriNet updatedNet, FileInputStream migrationXmlStream) {
        saveNetFile(updatedNet, migrationXmlStream)
        service.save(updatedNet)
    }

    Path saveNetFile(PetriNet net, InputStream xmlFile) throws IOException {
        File savedFile = new File(
                fileStorageConfiguration.getStorageArchived() + net.getStringId() + "-" + net.getTitle() + "-" + System.currentTimeMillis() + ".xml"
        )
        savedFile.getParentFile().mkdirs()
        net.setImportXmlPath(savedFile.getPath())
        copyInputStreamToFile(xmlFile, savedFile)
        return savedFile.toPath()
    }

    protected static void copyInputStreamToFile(InputStream inputStream, File file) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            int read
            byte[] bytes = new byte[1024]
            while ((read = inputStream.read(bytes)) != -1) {
                outputStream.write(bytes, 0, read)
            }
        }
    }

    def iterateAllActionsOfProcess(PetriNet net) {
        Map<String, String> allActionsMap = [:]
        Map<String, String> allFunctionsMap = [:]
        net.dataSet.values().each { field ->
            field.events.each { event ->
                event.value.preActions.each { action ->
                    allActionsMap.put("dataSet.${field.importId}.events.${event.key}.preActions.${action.id.toString()}" as String, action.getDefinition())
                }
                event.value.postActions.each { action ->
                    allActionsMap.put("dataSet.${field.importId}.events.${event.key}.postActions.${action.id.toString()}" as String, action.getDefinition())
                }
            }
        }
        net.transitions.each { trans ->
            trans.value.events.each { event ->
                event.value.preActions.each { action ->
                    allActionsMap.put("dataSet.${trans.value.importId}.events.${event.key}.preActions.${action.id.toString()}" as String, action.getDefinition())
                }
                event.value.postActions.each { action ->
                    allActionsMap.put("dataSet.${trans.value.importId}.events.${event.key}.postActions.${action.id.toString()}" as String, action.getDefinition())
                }
            }
        }
        net.transitions.each { trans ->
            trans.value.dataSet.each { dLogic ->
                dLogic.value.events.each { event ->
                    event.value.preActions.each { action ->
                        allActionsMap.put("transitions.dataSet.${dLogic.key}.events.${event.key}.preActions.${action.id.toString()}" as String, action.getDefinition())
                    }
                    event.value.postActions.each { action ->
                        allActionsMap.put("transitions.dataSet.${dLogic.key}.events.${event.key}.postActions.${action.id.toString()}" as String, action.getDefinition())
                    }
                }
            }
        }
        net.functions.each { fun ->
            allFunctionsMap.put("functions.${fun.getName()}" as String, fun.getDefinition())
        }
        return [allActionsMap, allFunctionsMap]
    }

    /**
     * replaces role permissions on transition with provided map e.g. ["roleId": ["perform": true]]
     * @param net
     * @param transitionId
     * @param role
     * @param permissions
     */
    void updateTransitionRoles(PetriNet net, String transitionId, ProcessRole role, Map<String, Boolean> permissions) {
        Transition trans = net.transitions.values().find { it.importId == transitionId }
        trans.roles[role.stringId] = permissions
    }

    void updateTransitionRoles(PetriNet net, String transitionId, String roleImportId, Map<String, Boolean> permissions) {
        ProcessRole role = net.roles.values().find { it.importId == roleImportId }
        updateTransitionRoles(net, transitionId, role, permissions)
    }

    Closure<PetriNet> updateTransitionRolesClosure(String transitionId, String roleImportId, Map<String, Boolean> permissions) {
        return { PetriNet petriNet, PetriNet reimported ->
            updateTransitionRoles(petriNet, transitionId, roleImportId, permissions)
            return petriNet
        }
    }

    Closure<PetriNet> updateRolesEventsClosure() {
        return { PetriNet petriNet, PetriNet reimported ->
            petriNet.roles.each { oldRole ->
                def reimportedRole = reimported.roles.find { it.value.importId == oldRole.value.importId }
                if (reimportedRole) {
                    oldRole.value.events = reimportedRole.value.events
                }
            }
            processRoleService.saveAll(petriNet.roles.values())
            return petriNet
        }
    }

    /**
     * Updates data set of existing Petri Net model with new values.
     * @param identifier
     * @param fileName
     */
    void updateDataSet(String identifier, String fileName, Closure<PetriNet> customUpdate = null) {
        PetriNet unionNet = service.getNewestVersionByIdentifier(identifier)
        PetriNet reimported = importer.importPetriNet(new File("src/main/resources/petriNets/" + fileName)).get()

        reimported = replaceUserFieldsRoleReferences(unionNet, reimported)

        unionNet.dataSet = reimported.dataSet

        if (customUpdate) {
            unionNet = customUpdate(unionNet, reimported)
        }

        service.save(unionNet)
        log.info("Migrated $identifier")
    }

    /**
     *  POZOR: nove roly referenceovnane vo fieldoch typu USER budu odignorovane! Treba ich manualne zmigrovat
     */
    private static PetriNet replaceUserFieldsRoleReferences(PetriNet originalNet, PetriNet reimportedNet) {
        Map<String, ProcessRole> originalNetRoles = [:] // importId: processRole
        originalNet.roles.forEach { name, role ->
            originalNetRoles.put(role.importId, role)
        }

        reimportedNet.dataSet.entrySet().stream().filter {
            it.value.type == FieldType.USER

        }.forEach { entry ->
            UserField field = (reimportedNet.dataSet[entry.key] as UserField)
            field.roles = field.roles.collect { roleId ->
                return replaceFieldRole(reimportedNet, roleId, originalNetRoles, originalNet)
            }.stream().filter { Objects.nonNull(it) }.collect()
        }

        reimportedNet.dataSet.entrySet().stream().filter {
            it.value.type == FieldType.USERLIST
        }.forEach { entry ->
            UserListField field = (reimportedNet.dataSet[entry.key] as UserListField)
            field.roles = field.roles.collect { roleId ->
                return replaceFieldRole(reimportedNet, roleId, originalNetRoles, originalNet)
            }.stream().filter { Objects.nonNull(it) }.collect()
        }

        return reimportedNet
    }

    private static String replaceFieldRole(PetriNet reimportedNet, String roleId, LinkedHashMap<String, ProcessRole> originalNetRoles, PetriNet originalNet) {
        Optional<ProcessRole> roleOpt = Optional.ofNullable(reimportedNet.roles[roleId])
        if (roleOpt.isPresent()) {
            ProcessRole oldRole = originalNetRoles[roleOpt.get().importId]

            if (!oldRole) {
                log.warn("Process role in process ${originalNet.identifier} ${originalNet.stringId} with import id ${roleOpt.get().importId} not found!")
                return null

            } else {
                return oldRole.stringId
            }

        } else {
            log.warn("Role not found! ${roleId}")
            return null
        }
    }

    /**
     * Create new role in existing Petri Net model.
     * @param identifier
     * @param id
     * @param title
     */
    def createRoleInNet(String identifier, String id, String title, Map<EventType, Event> events = [:]) {
        return createRoleInNet(identifier, id, new I18nString(title), events)
    }

    /**
     * Create new role in existing Petri Net model.
     * @param identifier
     * @param id
     * @param title
     */
    def createRoleInNet(String identifier, String id, I18nString title, Map<EventType, Event> events = [:]) {
        PetriNet net = service.getNewestVersionByIdentifier(identifier)

        if (net.roles.values().any { it.importId == id }) {
            log.warn("Role $id in $identifier already exists" as String)
            return
        }

        ProcessRole role = new ProcessRole()
        role.setImportId(id)
        role.setName(title)
        role.setEvents(events)
        role.setNetId(net.stringId)

        role = roleRepository.save(role)
        net.addRole(role)
        netRepository.save(net)

        return role
    }

    /**
     * Replaces events in roles from unionNet with events from roles from reimported
     */
    Closure<PetriNet> updateRoleEvents = { PetriNet unionNet, PetriNet reimported ->
        List<ProcessRole> newRoles = reimported.roles.values() as List
        List<ProcessRole> oldRoles = unionNet.roles.values() as List

        newRoles.each { newRole ->
            ProcessRole role = oldRoles.find { it.importId == newRole.importId }
            role.events = newRole.events
            roleRepository.save(role)
        }

        return unionNet
    }

    /**
     * Reloads tasks of provided case via TaskService,
     * handles useCase.petriNet internally
     * @param useCase
     * @param net
     * @return
     */
    def reloadTasks(Case useCase, PetriNet net) {
        setPetriNet(useCase, net)
        taskService.reloadTasks(useCase)
    }

    /**
     * Indexes provided case in elasticsearch
     * @param useCase
     */
    void elasticIndex(Case useCase) {
        try {
            assert useCase.petriNet
            elasticCaseService.indexNow(caseMappingService.transform(useCase))
        } catch (Exception ex) {
            if (useCase.lastModified == null) {
                log.error("Creating new lastModified date for $useCase.stringId")
                useCase.lastModified = LocalDateTime.now()
                elasticCaseService.indexNow(caseMappingService.transform(useCase))
            } else {
                log.error("Failed to index $useCase.stringId $useCase.processIdentifier $useCase.title", ex)
            }
        }
    }
    /**
     * Indexes provided task in elasticsearch
     * @param useCase
     */
    void elasticIndex(Task task) {
        try {
            elasticTaskService.indexNow(taskMappingService.transform(task))
        } catch (Exception ex) {
            log.error("Failed to index $task.stringId $task.caseTitle", ex)
        }
    }

    void updatePermissionsFromNet(Case useCase, PetriNet net) {
        useCase.permissions = net.getPermissions().entrySet().stream()
                .filter(role -> role.getValue().containsKey("delete") || role.getValue().containsKey("view"))
                .map(role -> {
                    Map<String, Boolean> permissionMap = new HashMap<>()
                    if (role.getValue().containsKey("delete"))
                        permissionMap.put("delete", role.getValue().get("delete"))
                    if (role.getValue().containsKey("view")) {
                        permissionMap.put("view", role.getValue().get("view"))
                    }
                    return new AbstractMap.SimpleEntry<>(role.getKey(), permissionMap)
                })
                .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue))
        useCase.resolveViewRoles()
        useCase.setEnabledRoles(net.getRoles().keySet())
        useCase.tasks.each { taskPair ->
            Transition newTransition = net.getTransition(taskPair.getTransition())
            Task oldTask = taskService.findOne(taskPair.getTask())
            oldTask.setProcessId(net.getStringId())
            oldTask.getRoles().clear()
            oldTask.setRoles(newTransition.roles)
            oldTask.setNegativeViewRoles(newTransition.negativeViewRoles)
            oldTask.resolveViewRoles()
            taskService.save(oldTask)
        }
    }

    /**
     * adds role with permissions to existing tasks of net
     * @param role
     * @param net
     * @param transitionIds
     * @param permissions
     */
    void addRoleToExistingTasks(ProcessRole role, PetriNet net, List<String> transitionIds, Map<String, Boolean> permissions) {
        updateTasks({ Task task ->
            log.info("Added role '${role.getName()}' with roleId=${role.getImportId()} to transitionId=${task.getTransitionId()} in task ${task.stringId}")
            task.addRole(role.getStringId(), permissions)
            task.resolveViewRoles()
        }, QTask.task.transitionId.in(transitionIds) & QTask.task.processId.eq(net.getStringId()))
    }

    void setPetriNet(Case useCase) {
        setPetriNet(useCase, service.get(useCase.getPetriNetObjectId()))
    }

    void setPetriNet(Case useCase, PetriNet net) {
        PetriNet model = net.clone()
        model.initializeTokens(useCase.getActivePlaces())
        model.initializeArcs(useCase.getDataSet())
        useCase.setPetriNet(model)
    }

    void migratePetriNet(Case useCase, PetriNet newNet) {
        useCase.setPetriNetObjectId(newNet.objectId)
    }

    /**
     * Adds permission assigned into net for each role in all transitions
     */
    Closure addAssignedPermission = { PetriNet unionNet, PetriNet reimported ->
        unionNet.transitions.each { transition ->
            transition.value.roles.each { role ->
                role.value.put("assigned", true)
            }
        }

        return unionNet
    }

    /**
     * Delete given data fields from useCase
     * @param useCase
     * @param toDelete - list of fields IDs
     */
    void deleteDataFields(Case useCase, List<String> toDelete) {
        toDelete.each { dataFieldID ->
            useCase.dataSet.remove(dataFieldID)
        }
    }

    /**
     * change value of given data fields from number to text
     * @param useCase
     * @param toChange - list of fields IDs
     */
    void changeDataFieldsValueFromNumberToText(Case useCase, List<String> toChange) {
        toChange.each { dataFieldID ->
            if (useCase.dataSet[dataFieldID].value && (useCase.dataSet[dataFieldID].value != null || useCase.dataSet[dataFieldID].value != "")) {
                double value = useCase.dataSet[dataFieldID].value as double
                useCase.dataSet[dataFieldID].value = value as String
            }
        }
    }

    /**
     * add new data fields with their init value into useCase
     * @param useCase
     * @param toAdd - Map<field id, init value of field>
     */
    void addTextDataFields(Case useCase, Map<String, String> toAdd) {
        toAdd.each { dataFieldID, value ->
            useCase.dataSet[dataFieldID] = new DataField(value)
        }
    }

    /**
     * change value of given data fields from enumeration to multichoice
     * @param useCase
     * @param toChange - list of fields IDs
     */
    void changeDataFieldsValueFromEnumerationToMultichoice(Case useCase, List<String> toChange) {
        toChange.each { dataFieldID ->
            if (useCase.dataSet[dataFieldID].value && useCase.dataSet[dataFieldID].value != null) {
                def value
                if (useCase.dataSet[dataFieldID].value instanceof I18nString) {
                    value = useCase.dataSet[dataFieldID].value as I18nString
                } else {
                    value = new I18nString(useCase.dataSet[dataFieldID].value as String)
                }

                def newSet = new HashSet<I18nString>()
                newSet.add(value)
                useCase.dataSet[dataFieldID].value = newSet
            }
        }
    }

    /**
     * add new choices into enumeration or multichoice
     * @param useCase
     * @param toAdd - Map<field id, list of choices to add into data data field>
     */
    void addChoices(Case useCase, Map<String, List<String>> toAdd) {
        toAdd.each { dataFieldID, newChoices ->
            if (useCase.dataSet[dataFieldID].choices == null) {
                useCase.dataSet[dataFieldID].setChoices(new HashSet<I18nString>())
            }

            newChoices.each {
                useCase.dataSet[dataFieldID].choices.add(new I18nString(it))
            }
        }
    }

    /**
     * remove choices from enumeration or multichoice
     * @param useCase
     * @param toAdd - Map<field id, list of choices to add into data data field>
     */
    void removeChoices(Case useCase, Map<String, List<String>> toRemove) {
        toRemove.each { dataFieldID, choicesToRemove ->
            if (useCase.dataSet[dataFieldID].value != null) {
                (useCase.dataSet[dataFieldID].value as Set).removeAll(choicesToRemove)
            }

            if (useCase.dataSet[dataFieldID].choices != null) {
                useCase.dataSet[dataFieldID].choices.removeAll(choicesToRemove)
            }
        }
    }

    void resolveDataOrder(PetriNet petriNet) {
        Collator skCollator = Collator.getInstance(new Locale("sk", "SK"))
        List<Field> fields = new LinkedList<>(petriNet.getDataSet().values())
        fields = fields.stream().sorted({ f1, f2 ->
            int comparedTypes = f2.type.name <=> f1.type.name
            if (comparedTypes != 0) return comparedTypes
            return skCollator.compare((f1.name?.defaultValue ?: f1.stringId), (f2.name?.defaultValue ?: f2.stringId))
        }).collect(Collectors.toList())
        petriNet.dataSet = fields.collectEntries { [(it.getStringId()): (it)] } as Map<String, Field>
    }

    def reloadTaskUserRefs(Case useCase, Task task) {
        Transition transition = useCase.petriNet.getTransition(task.transitionId)
        task.getUserRefs().clear()
        for (Map.Entry<String, Map<String, Boolean>> entry : transition.getUserRefs().entrySet()) {
            task.addUserRef(entry.getKey(), entry.getValue())
        }
        task.resolveViewUserRefs()
        taskService.save(task)
        taskService.resolveUserRef(task, useCase)
    }

    def reloadTaskUserRefs(Case useCase, String transitionId) {
        try {
            if (useCase.tasks.find { it.transition == transitionId } != null) {
                Task task = taskService.findOne(useCase.tasks.find { it.transition == transitionId }.task)
                setPetriNet(useCase)
                reloadTaskUserRefs(useCase, task)
            }
        } catch (Exception e) {
            log.error("Couldn't reload userRefs on transition" + transitionId, e)
        }
    }

    def reloadCaseUserRefs(Case useCase, PetriNet petriNet) {
        useCase.setUserRefs(petriNet.getUserRefs())
        workflowService.resolveUserRef(useCase)
    }
}
