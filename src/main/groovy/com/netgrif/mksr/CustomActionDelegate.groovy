package com.netgrif.mksr

import com.netgrif.application.engine.auth.domain.Authority
import com.netgrif.application.engine.auth.domain.IUser
import com.netgrif.application.engine.auth.domain.User
import com.netgrif.application.engine.auth.domain.UserState
import com.netgrif.application.engine.petrinet.domain.I18nString
import com.netgrif.application.engine.petrinet.domain.PetriNet
import com.netgrif.application.engine.petrinet.domain.Transition
import com.netgrif.application.engine.petrinet.domain.UriContentType
import com.netgrif.application.engine.petrinet.domain.UriNode
import com.netgrif.application.engine.petrinet.domain.arcs.Arc
import com.netgrif.application.engine.petrinet.domain.dataset.Field
import com.netgrif.application.engine.petrinet.domain.dataset.logic.action.ActionDelegate
import com.netgrif.application.engine.petrinet.domain.roles.ProcessRole
import com.netgrif.application.engine.petrinet.domain.version.Version
import com.netgrif.application.engine.workflow.domain.Case
import com.netgrif.application.engine.workflow.domain.DataField
import com.netgrif.mksr.migration.MigrationHelper
import com.netgrif.mksr.petrinet.domain.UriNodeData
import com.netgrif.mksr.petrinet.domain.UriNodeDataRepository
import com.netgrif.mksr.startup.NetRunner
import org.apache.commons.lang3.StringUtils
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

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
            createUri(uri, UriContentType.DEFAULT)}
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

    def addFieldToSubject(String fieldId, def value) {
        migrationHelper.updateCasesCursor({ Case useCase ->
            useCase.dataSet.put(fieldId, new DataField(value))
            useCase.immediateData(fieldId)
            useCase.dataSet.put(fieldId + "_tmp", new DataField(value))
            migrationHelper.elasticIndex(useCase)
        }, NetRunner.PetriNetEnum.SUBJECT.identifier)
    }

    def reloadTasksOnSubject() {
        migrationHelper.updateCasesCursor({ Case useCase ->
            migrationHelper.setPetriNet(useCase)
            taskService.reloadTasks(useCase)
        }, NetRunner.PetriNetEnum.SUBJECT.identifier)
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

    def getNetName() {

    }

    def test(Field sector_map, Field register_map) {
        def sector = uriService.findById(sector_map.value)
        def fullQuery = "processIdentifier:preference_item AND uriNodeId:${sector._id.toString()}"
        def registerCases = findCasesElastic(fullQuery, org.springframework.data.domain.PageRequest.of(0, 100))
        change register_map value { null }
        change register_map options { registerCases.collectEntries { [it.dataSet["nodePath"].value, it.dataSet["menu_name"].value.defaultValue] } }
    }

}