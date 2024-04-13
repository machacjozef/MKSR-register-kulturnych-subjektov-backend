package com.netgrif.mksr

import com.netgrif.application.engine.auth.domain.Authority
import com.netgrif.application.engine.auth.domain.IUser
import com.netgrif.application.engine.auth.domain.LoggedUser
import com.netgrif.application.engine.auth.domain.User
import com.netgrif.application.engine.auth.domain.UserState
import com.netgrif.application.engine.petrinet.domain.DataFieldLogic
import com.netgrif.application.engine.petrinet.domain.I18nString
import com.netgrif.application.engine.petrinet.domain.PetriNet
import com.netgrif.application.engine.petrinet.domain.UriContentType
import com.netgrif.application.engine.petrinet.domain.UriNode
import com.netgrif.application.engine.petrinet.domain.dataset.BooleanField
import com.netgrif.application.engine.petrinet.domain.dataset.DateField
import com.netgrif.application.engine.petrinet.domain.dataset.DateTimeField
import com.netgrif.application.engine.petrinet.domain.dataset.Field
import com.netgrif.application.engine.petrinet.domain.dataset.logic.FieldBehavior
import com.netgrif.application.engine.petrinet.domain.dataset.logic.FieldLayout
import com.netgrif.application.engine.petrinet.domain.dataset.NumberField
import com.netgrif.application.engine.petrinet.domain.dataset.TextField
import com.netgrif.application.engine.petrinet.domain.dataset.UserFieldValue
import com.netgrif.application.engine.petrinet.domain.dataset.logic.action.ActionDelegate
import com.netgrif.application.engine.petrinet.domain.events.DataEvent
import com.netgrif.application.engine.petrinet.domain.events.DataEventType
import com.netgrif.application.engine.petrinet.domain.roles.ProcessRole
import com.netgrif.application.engine.startup.ImportHelper
import com.netgrif.application.engine.petrinet.domain.version.Version
import com.netgrif.application.engine.workflow.domain.Case
import com.netgrif.application.engine.workflow.domain.DataField
import com.netgrif.mksr.migration.MigrationHelper
import com.netgrif.mksr.petrinet.domain.UriNodeData
import com.netgrif.mksr.petrinet.domain.UriNodeDataRepository
import com.netgrif.mksr.startup.NetRunner
import org.apache.commons.lang3.StringUtils
import org.bson.types.ObjectId
import org.drools.core.util.LinkedList
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.*


import java.time.LocalDateTime

@Component
class CustomActionDelegate extends ActionDelegate {

    @Autowired
    private UriNodeDataRepository uriNodeDataRepository

    @Autowired
    private MigrationHelper migrationHelper

    /**
     * create or update menu item of specified type
     * @param id
     * @param uri
     * @param type
     * @param query
     * @param icon
     * @param title
     * @param allowedNets
     * @param roles
     * @param bannedRoles
     * @return
     */
    Case createOrUpdateMenuItem(String id, String uri, String type, String query, String icon, String title, List<String> allowedNets, Map<String, String> roles = [:], Map<String, String> bannedRoles = [:]) {
        collectRolesForPreferenceItem(roles)
        Case menuItem = findMenuItem(id)
        if (!menuItem) {
            Case filter = createFilter(title, query, type, allowedNets, icon, "private", null)
            createUri(uri, UriContentType.DEFAULT)
            return createMenuItem(uri, id, filter, roles, bannedRoles)
        } else {
            Case filter = getFilterFromMenuItem(menuItem)
            changeFilter filter query { query }
            changeFilter filter allowedNets { allowedNets }
            changeFilter filter title { title }
            changeFilter filter icon { icon }
            changeMenuItem menuItem allowedRoles { roles }
            changeMenuItem menuItem bannedRoles { bannedRoles }
            changeMenuItem menuItem uri { uri }
            changeMenuItem menuItem filter { filter }
            return workflowService.findOne(menuItem.getStringId())
        }
    }

    void createOrUpdateFolder(String id, String uri) {
        Case menuItem = findMenuItem(id)
        if (!menuItem) {
            createUri(uri, UriContentType.DEFAULT)
        }
    }

    private Map<String, I18nString> collectRolesForPreferenceItem(Map<String, String> roles) {
        Map<String, PetriNet> temp = [:]
        return roles.collectEntries { entry ->
            if (!temp.containsKey(entry.value)) {
                temp.put(entry.value, petriNetService.getNewestVersionByIdentifier(entry.value))
            }
            PetriNet net = temp[entry.value]
            def foundEntry = net.roles.find { it.value.importId == entry.key }
            if (!foundEntry) {
                throw new IllegalArgumentException("No role $entry.key $net.identifier")
            }
            ProcessRole role = foundEntry.value
            return [(role.importId + ":" + net.identifier), ("$role.name ($net.title)" as String)]
        } as Map<String, I18nString>
    }

    /**
     * update menu item property
     * @param id
     * @param section to put this item in instead of default (currently only "settings" supported)
     * @return menu item
     */
    Case updateMenuItemSection(String id, String section = "settings") {
        Case menuItem = findMenuItem(id)
        if (!menuItem) {
            return null
        }
        return updateMenuItemSection(menuItem, section)
    }

    Case updateMenuItemSection(Case menuItem, String section = "settings") {
        menuItem.dataSet["custom_drawer_section"].value = section
        return workflowService.save(menuItem)
    }

    /**
     * set roles to uri node based on uriPaths
     * all roles of all processes from provided uriPaths will be able to see the node
     * @param uri
     * @param uriPaths
     */
    void setUriNodeDataRolesByPaths(String uri, List<String> uriPaths) {
        List<PetriNet> nets = uriPaths.collect {
            UriNode node = getUri(it) as UriNode
            if (!node) return null
            return petriNetService.findAllByUriNodeId(node.stringId)
        }.findAll { it != null }.flatten() as List<PetriNet>
        List<String> roleIds = nets.collect { it.roles.keySet() as List }.flatten() as List<String>
        setUriNodeDataRoles(uri, roleIds)
    }

    /**
     * set roles to uri node
     * @param uri
     * @param netRoles [net_identifier: [admin, system, ...]
     */
    void setUriNodeDataRoles(String uri, Map<String, List<String>> netRoles) {
        List<ProcessRole> roles = netRoles.collect { entry ->
            def net = petriNetService.getNewestVersionByIdentifier(entry.key)
            return net.roles.values().findAll { role -> entry.value.any { roleImportId -> roleImportId == role.importId } }
        }.flatten() as List<ProcessRole>
        setUriNodeDataRoles(uri, roles.stringId as List)
    }

    /**
     * set filters to uri node
     * @param uri
     * @param menu item identifiers
     */
    void setUriNodeDataFilters(String uri, List<String> menuItemIdentifiers) {
        UriNode uriNode = getUri(uri) as UriNode
        uriNodeDataRepository.findByUriNodeId(uriNode.getStringId()).ifPresentOrElse(data -> {
            data.setMenuItemIdentifiers(menuItemIdentifiers)
            uriNodeDataRepository.save(data)
        }, () -> {
            uriNodeDataRepository.save(new UriNodeData(uriNode.getStringId(), null, null, false, false, null, menuItemIdentifiers))
        })
    }

    /**
     * set roles to uri node for counters
     * @param uri
     * @param roleIds role stringIds
     */
    void setUriNodeDataRoles(String uri, List<String> roleIds) {
        UriNode uriNode = getUri(uri) as UriNode
        uriNodeDataRepository.findByUriNodeId(uriNode.getStringId()).ifPresentOrElse(data -> {
            data.setProcessRolesIds(roleIds as Set)
            uriNodeDataRepository.save(data)
        }, () -> {
            uriNodeDataRepository.save(new UriNodeData(uriNode.getStringId(), null, null, false, false, roleIds as Set, null))
        })
    }

    /**
     * set custom uri node data
     * @param uri
     * @param title
     * @param section - "settings" or "archive" if root, else null
     * @param icon
     * @param isSvgIcon
     * @param isHidden
     * @param roleIds - if null, no restriction
     */
    void setUriNodeData(String uri, String title, String section, String icon, boolean isSvgIcon = false, boolean isHidden = false, List<String> roleIds = null) {
        UriNode uriNode = getUri(uri) as UriNode
        uriNode.setName(title)
        uriService.save(uriNode)
        uriNodeDataRepository.findByUriNodeId(uriNode.getStringId()).ifPresentOrElse(data -> {
            data.setIcon(icon)
            data.setSection(section)
            data.setIconSvg(isSvgIcon)
            data.setProcessRolesIds(roleIds as Set)
            data.setHidden(isHidden)
            uriNodeDataRepository.save(data)
        }, () -> {
            uriNodeDataRepository.save(new UriNodeData(uriNode.getStringId(), section, icon, isSvgIcon, isHidden, roleIds as Set, null))
        })
    }

    boolean canUserAccessMenuItem(Case menuItem, IUser user) {
        Map<String, I18nString> allowedRoles = menuItem.getDataField("allowed_roles").options
        Map<String, I18nString> bannedRoles = menuItem.getDataField("banned_roles").options
        boolean hasAllowedRole = !allowedRoles || allowedRoles.keySet().any { encoded ->
            def importIdToNet = parseRoleFromMenuItemRoles(encoded)
            def net = petriNetService.getNewestVersionByIdentifier(importIdToNet.values()[0])
            return user.processRoles.any { it.importId == importIdToNet.keySet()[0] && it.netId == net.stringId }
        }
        boolean hasBannedRole = bannedRoles && bannedRoles.keySet().any { encoded ->
            def importIdToNet = parseRoleFromMenuItemRoles(encoded)
            def net = petriNetService.getNewestVersionByIdentifier(importIdToNet.values()[0])
            return user.processRoles.any { it.importId == importIdToNet.keySet()[0] && it.netId == net.stringId }
        }
        return hasAllowedRole && !hasBannedRole
    }

    protected static Map<String, String> parseRoleFromMenuItemRoles(String encoded) {
        def split = encoded.split(":")
        return [(split[0]): split[1]]
    }

    def createNewUser(String name, String surname, String email, String password) {
        if (userService.findByEmail(email, true) != null) {
            throw new IllegalArgumentException("Používateľ s rovnakým emailom už bol vytvorený")
        }
        userService.saveNew(new User(
                name: name,
                surname: surname,
                email: email,
                password: password,
                state: UserState.ACTIVE,
                authorities: [] as Set<Authority>,
                processRoles: [] as Set<ProcessRole>))
    }

    def addFieldToSubject(String fieldId,String netIdentifier, def value) {
        migrationHelper.updateCasesCursor({ Case useCase ->
            useCase.dataSet.put(fieldId, new DataField(value))
            useCase.immediateDataFields.add(fieldId)
            useCase.dataSet.put(fieldId + "_tmp", new DataField(value))
            migrationHelper.elasticIndex(useCase)
        }, netIdentifier)
    }

    def reloadTasksOnSubject() {
        migrationHelper.updateCasesCursor({ Case useCase ->
            migrationHelper.setPetriNet(useCase)
            taskService.reloadTasks(useCase)
        }, NetRunner.PetriNetEnum.SUBJECT.identifier)
    }

    def revertChanges(){
        List<String> fieldIds = useCase.getPetriNet().getTransition("dynamic").dataSet.collect { it.getKey() }
        fieldIds.each { fieldId ->
            change useCase.getField(fieldId) value { useCase.dataSet.get(fieldId.replace("_tmp","")).getValue() }
        }
    }

    def saveChanges(){
        List<String> fieldIds = useCase.getPetriNet().getTransition("dynamic").dataSet.collect { it.getKey() }
        fieldIds.each { fieldId ->
            change useCase.getField(fieldId.replace("_tmp","")) value { useCase.dataSet.get(fieldId).getValue() }
        }
    }
    String textPreprocess(String text){
        return StringUtils.stripAccents(text).toLowerCase().replaceAll("\\.","_").replaceAll(" ","")
    }

    def createNewNet(String newIdentifier) {
        def templateNet = petriNetService.getNewestVersionByIdentifier("subject")
        def newNet = templateNet.clone()
        newNet.identifier = newIdentifier
        newNet.objectId = new ObjectId()
        Version v = new Version()
        v.major = 1
        v.minor = 0
        v.patch = 0
        petriNetService.save(newNet)
    }

    def createNewDataSetLogic(int x, int y) {
        DataFieldLogic dfl = new DataFieldLogic()
        dfl.behavior = [FieldBehavior.VISIBLE]
        dfl.events = [:]
        DataEvent deg = new DataEvent()
        deg.type = DataEventType.GET
        deg.preActions = []
        deg.postActions = []
        dfl.events.put(DataEventType.GET, deg)
        DataEvent seg = new DataEvent()
        seg.type = DataEventType.GET
        seg.preActions = []
        seg.postActions = []
        dfl.events.put(DataEventType.SET, seg)
        FieldLayout fl = new FieldLayout()
        fl.template = "material"
        fl.appearance = "outline"
        fl.rows = 1
        fl.cols = 2
        fl.x = x
        fl.y = y
        dfl.setLayout(fl)
        return dfl
    }

    PetriNet appendToNetNewField(PetriNet net, String id, String type) {
        net.getDataSet().put(id.trim().toLowerCase(), buildField(type))
        net.getDataSet().put(id.trim().toLowerCase() + "_tmp", buildField(type))
        return net
    }

    def buildField(String type) {
        Field field
        switch (type) {
            case "boolean":
                field = new BooleanField();
                field.setDefaultValue(false)
                break
            case "date":
                field = new DateField()
                break
            case "dateTime":
                field = new DateTimeField()
                break
            case "number":
                field = new NumberField()
                field.setDefaultValue(0D)
                break
            default:
                field = new TextField("")
                break
        }
        return field
    }


    String importData(PetriNet net, def field, def zoznam) {
        String logy = ""
        def excelData = loadDataBasedOnFileExtension(field.value.name, field.value.path, false)

        if (excelData == null) {
            throw new IllegalArgumentException("Zly format")
        }

        def importIdList = []
        zoznam.each { it ->
            Case caze = workflowService.findOne(taskService.findOne(it).getCaseId())
            if (caze.dataSet.get("register_ids_clone").value == "new") {
                importIdList.add(caze.dataSet.get("new_title").value as String)
            } else {
                importIdList.add(caze.dataSet.get("register_ids_clone").value as String)
            }
        }

        excelData.data.each { row -> {
            Case caze = createCase(net.identifier)
            row.each { data -> {
                int index = row.indexOf(data)
                caze.dataSet[importIdList.get(index)].value = data
                }}
            logy += "Pridavam zaznam: ${row} /n"
            workflowService.save(caze)
            }
        }
        return logy
    }


    List<String> getHeaders(def netName, def importName, def field, Boolean skipData) {
        def excelData = loadDataBasedOnFileExtension(field.value.name, field.value.path, skipData)

        if (excelData == null) {
            throw new IllegalArgumentException("Zly format")
        }

        println("Hlavičky stĺpcov:")
        println(excelData.headers.join('\t'))

        LinkedHashMap<String, I18nString> options = []
        options.put("new", new I18nString("Nový"))

        def referencedFields = netName.dataSet.collectEntries { [it.key, it.value.name.defaultValue] }

        referencedFields.each { it ->
            {
                if (!(it.key.contains("_tmp"))) {
                    options.put(it.key, it.value)
                }
            }
        }

        def multi = []
        List<String> tasky = []
        excelData.headers.each { name ->
            Case caze = createCase("importMapper", importName + "-" + name)
            def aa = porovnajKluc(name, options)
            if (aa == null) {
                multi = []
            } else {
                String bb = aa?.collect().toSet().first()?.key
                println(bb)
                multi = bb
            }
            Map data = [
                    "importName"        : [
                            "value": name,
                            "type" : "text"
                    ],
                    "new_title"         : [
                            "value": name,
                            "type" : "text"
                    ],
                    "register_ids_clone": [
                            "value"  : multi,
                            "options": options,
                            "type"   : "enumeration_map"
                    ]
            ]
            caze = dataService.setData(
                    caze.tasks.find { it.transition == "t1" }.task,
                    ImportHelper.populateDataset(data)
            ).getCase()
            workflowService.save(caze)
            tasky.add(caze.tasks.find { it.transition == "t1" }.task)
        }
        return tasky
    }

    def getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.')
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex + 1)
        }
        return ""
    }

    def loadDataBasedOnFileExtension(String name, String filePath, boolean skipData) {
        def extension = getFileExtension(name)

        switch (extension.toLowerCase()) {
            case 'xlsx':
                return readExcelData(filePath, skipData)
            case 'csv':
                return readCsvData(filePath, skipData)
            default:
                log.warn("Nepodporovaná prípona súboru: $extension")
                return null
        }
    }

    def readExcelData(String filePath, boolean skipData) {
        FileInputStream fileInputStream = new FileInputStream(filePath)
        XSSFWorkbook workbook = new XSSFWorkbook(fileInputStream)
        XSSFSheet sheet = workbook.getSheetAt(0)  // TODO iba prvy!

        List<List<String>> data = []
        List<String> headers = null

        for (Row row : sheet) {
            List<String> rowData = []

            for (Cell cell : row) {
                String cellValue = getCellValueAsString(cell)
                rowData.add(cellValue)
            }

            if (headers == null) {
                headers = rowData
                if (skipData) {
                    continue
                }
            } else {
                data.add(rowData)
            }
        }
        workbook.close()
        return [headers: headers, data: data]
    }

    def getCellValueAsString(Cell cell) {
        if (cell == null) {
            return ""
        }
        switch (cell.getCellType()) {
            case CellType.STRING:
                return cell.getStringCellValue()
            case CellType.NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString()
                } else {
                    return cell.getNumericCellValue().toString()
                }
            case CellType.BOOLEAN:
                return cell.getBooleanCellValue().toString()
            case CellType.BLANK:
                return ""
            default:
                return ""
        }
    }

    def readCsvData(String filePath, boolean skipData) {
        def headers = []
        def data = []
        def file = new File(filePath)

        try {

        } catch (Exception e) {
            println("Chyba pri načítaní súboru CSV: $e.message")
            return null
        }

        return [headers: headers, data: data]
    }

    def porovnajKluc(kluc, zoznam) {
        def najlepsiKluc = null
        def najlepsiaPodobnost = 0

        zoznam.each { it ->
            String existujuciKluc = it.value
            def podobnost = podobnostKlucov(kluc.toLowerCase(), existujuciKluc.toLowerCase())

            if (podobnost > najlepsiaPodobnost && podobnost > 0.5) {
                najlepsiKluc = existujuciKluc
                najlepsiaPodobnost = podobnost
            }
        }

        if (najlepsiKluc) {
            return zoznam.findAll { it.value == najlepsiKluc }
        } else {
            return null
        }
    }

    def podobnostKlucov(kluc, existujuciKluc) {
        def dlzkaKluc = kluc.length()
        def dlzkaExistujucehoKluca = existujuciKluc.length()
        def mensiaDlzka = Math.min(dlzkaKluc, dlzkaExistujucehoKluca)

        def zhodneZnaky = 0
        for (int i = 0; i < mensiaDlzka; i++) {
            if (kluc[i] == existujuciKluc[i]) {
                zhodneZnaky++
            }
        }
        return zhodneZnaky / mensiaDlzka.toDouble()
    }

    def generateDataField(PetriNet netValue, def zoznam) {
        zoznam.each { it ->
            Case caze = workflowService.findOne(taskService.findOne(it).getCaseId())
            if (caze.dataSet.get("register_ids_clone").value == "new") {
                netValue = appendToNetNewField(netValue, caze.dataSet.get("new_title").value, caze.dataSet.get("new_type").value)
            }
        }
        petriNetService.save(netValue)
        println("siet bola aktualizovana: " + netValue.getIdentifier())
    }



    def revertChanges(){
        List<String> fieldIds = useCase.getPetriNet().getTransition("dynamic").dataSet.collect { it.getKey() }
        fieldIds.each { fieldId ->
            change useCase.getField(fieldId) value { useCase.dataSet.get(fieldId.replace("_tmp","")).getValue() }
        }
    }

    def saveChanges(){
        List<String> fieldIds = useCase.getPetriNet().getTransition("dynamic").dataSet.collect { it.getKey() }
        fieldIds.each { fieldId ->
            if(useCase.dataSet.get(fieldId.replace("_tmp","")).getValue() == useCase.dataSet.get(fieldId).getValue()){
                return
            }
            createAuditCase(
                    "Change from :" + useCase.dataSet.get(fieldId.replace("_tmp","")).value + " to " + useCase.dataSet.get(fieldId).getValue(),
                    useCase.stringId,
                    fieldId.replace("_tmp",""),
                    loggedUser(),
                    LocalDateTime.now()
            )
            change useCase.getField(fieldId.replace("_tmp","")) value { useCase.dataSet.get(fieldId).getValue() }
        }
    }

    @Async
    def createAuditCase(String detail, String idOfSubject, String fieldId, IUser person, LocalDateTime timeOfChange ){
        Case auditCase = createCase("audit")
        auditCase.dataSet.get("id_of_subject").value = idOfSubject
        auditCase.dataSet.get("field_id").value = fieldId
        auditCase.dataSet.get("person").value = new UserFieldValue(userService.findById(person.getStringId(),false))
        auditCase.dataSet.get("detail").value = detail
        auditCase.dataSet.get("time_of_change").value = timeOfChange
        workflowService.save(auditCase)
    }
}