import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.user.util.UserManager
import com.onresolve.scriptrunner.canned.jira.utils.ConditionUtils
import com.onresolve.scriptrunner.canned.jira.workflow.postfunctions.CloneIssue
import com.onresolve.scriptrunner.runner.ScriptRunnerImpl
import org.apache.log4j.Logger
import org.apache.log4j.Level

import com.atlassian.jira.config.properties.APKeys
import com.onresolve.scriptrunner.canned.jira.utils.AbstractCloneIssue
import com.onresolve.scriptrunner.canned.jira.utils.CannedScriptUtils
import groovy.xml.MarkupBuilder

import com.onresolve.scriptrunner.canned.jira.admin.CopyProject

import com.atlassian.jira.issue.link.LinkCollectionImpl
import com.atlassian.jira.issue.link.IssueLink

import com.atlassian.jira.user.ApplicationUser

import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.event.type.EventDispatchOption

def log = Logger.getLogger(getClass())
log.setLevel(Level.DEBUG)

def issueService = ComponentAccessor.getIssueService()
def issueManager = ComponentAccessor.getIssueManager()
def linkManager = ComponentAccessor.getIssueLinkManager()
def userManager = ComponentAccessor.getUserManager()
def projectManager = ComponentAccessor.getProjectManager()
def pluginAccessor = ComponentAccessor.getPluginAccessor()
def projectPropertiesManager = ComponentAccessor.getOSGiComponentInstanceOfType(pluginAccessor
       .getClassLoader().findClass("com.tse.jira.projectproperties.plugin.api.ProjectPropertiesAOMgr"))


//Read the target project and assignee from the current project's properties
def targetProject = ""
ApplicationUser newAssignee
projectPropertiesManager.getProjectPropertiesByProjectKey(issue.getProjectObject().getKey()).each {property ->
	if (property.getPropertyKey() == "bauProject") {
		targetProject = property.getPropertyValue()
	}
    if (property.getPropertyKey() == "defaultAssignee") {      
        String val = property.getPropertyValue()
        log.debug("assignee name: " + val)
        newAssignee = userManager.getUserByName(val)
        log.debug("new assignee: " + newAssignee)
    }
}


//def newAssignee = userManager.getUserByName("Nabeel.Ali")
//log.debug("new assignee name: " + newAssignee.getName())

def cloneIssueBean = ScriptRunnerImpl.scriptRunner.createBean(CloneIssue)

def executionContext = [
    issue: issue,
]

def additionalCode = """
issue.summary = '${issue.getSummary()}'
"""
    
def linkTypeRelatesTo = CannedScriptUtils.getAllLinkTypesWithInwards(true).find { it.value == "relates to" }.key.toString()

def inputs = [
    'FIELD_TARGET_PROJECT' : targetProject,
    'FIELD_LINK_TYPE' : linkTypeRelatesTo,
    'FIELD_SELECTED_FIELDS': null, //clone all the fields
    (ConditionUtils.FIELD_ADDITIONAL_SCRIPT): [additionalCode, ""]
] as Map<String, Object>

def cloneAlreadyExists = false 
List<IssueLink> linkedIssues = linkManager.getOutwardLinks(issue.getId())
linkedIssues.each{ linkedIssue -> 
    
    def currentIssue = linkedIssue.getDestinationObject()
    log.debug(currentIssue.getKey())
    def currentIssueProject = currentIssue.getProjectObject().getKey()
    log.debug(currentIssueProject)
    if (currentIssueProject == targetProject) {
        cloneAlreadyExists = true
    }
}

def newIssueKey = ""

// check for duplicate before cloning/linking
if (cloneAlreadyExists == true) {
    inputs = inputs.minus(['FIELD_LINK_TYPE' : linkTypeRelatesTo])
    log.debug("Clone already exists")
}
else {
    //clone the issue
    def newIssueObj = cloneIssueBean.execute(inputs, executionContext)

    //retrieve the issue key of the clone
    def newIssueInfo = newIssueObj.toString().split(",")
    def unformattedKey = newIssueInfo[newIssueInfo.size()-1].substring(10)
    newIssueKey = unformattedKey.subSequence(0, unformattedKey.length()-1).toString()
    log.debug(newIssueKey)

    //assign user to issue
    def newIssue = issueManager.getIssueObject(newIssueKey)
    log.debug(newIssue.getSummary())

    

    def user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
    log.debug(user.getName())
    def assigneeUpdate = newIssue.setAssignee(newAssignee)
    issueManager.updateIssue(user, newIssue, EventDispatchOption.ISSUE_UPDATED, false)

}
