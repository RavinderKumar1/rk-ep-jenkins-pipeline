#!/usr/bin/env groovy
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.json.JsonSlurperClassic
import java.io.StringWriter
import java.io.PrintWriter
import java.net.URLEncoder
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.TimeZone;
import groovy.transform.InheritConstructors


import org.apache.commons.lang.StringUtils

import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.support.actions.LogStorageAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import jenkins.plugins.git.GitStep;
import hudson.triggers.SCMTrigger;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.StringParameterDefinition;

import org.jfrog.hudson.pipeline.common.types.buildInfo.Env;

import hudson.security.AuthorizationMatrixProperty;
import hudson.model.*;

import com.cloudbees.groovy.cps.NonCPS;

@InheritConstructors
class SharedLibException extends Exception {
}

@InheritConstructors
class PostBuildException extends Exception {
}

@groovy.transform.Field
        def GIT_URLS = null;

def get_default_artifactory() {
    return env.DEFAULT_ARTIFACTORY? env.DEFAULT_ARTIFACTORY : "artifactory"
}
boolean someLibraryMethod() {
    false
}
def get_rd_artifactory() {
    return "artifactory-rd"
}

// Determine the  job_type develop, release, hotfix

def is_develop_job(){
    boolean is_develop = true
    if(env.RELEASE_BRANCH){
        is_develop = false
    }
    return is_develop
}

def is_release_job(){
    return !(is_develop_job())
}

def is_hotfix_job(){
    if(is_develop_job()) {
        return false
    }
    else {
        return (env.BUILD_TAG.split("\\.")[1] > 0)
    }
}

def is_fedramp_job() {
    boolean is_fedramp = false
    if ( "${env.JOB_NAME}".contains("fedramp") || "${env.JOB_NAME}".contains("fips") ) {
        is_fedramp = true
    }
    return is_fedramp
}

def get_apache_builds_dir(){
    if(is_develop_job()) {
        return "develop"
    }
    else if (is_release_job()) {
        return "release"
    }
    // It will not go in this section as job is either develop or release
    else {
        return "production"
    }
}

def get_apache_build_tag(){
    if(is_develop_job()) {
        return "develop"
    }
    else if (is_release_job()) {
        return get_branch_name()
    }
    // It will not go in this section as job is either develop or release
    else {
        return "production"
    }
}


def get_artifactory_channel(String repo_prefix = null) {
    String prefix = repo_prefix? repo_prefix: ""
    if (is_release_job()) {
        return prefix+"release"
    }
    else{
        return prefix+"develop"
    }
}

def get_artifactory_registry(String repo_prefix = null) {
    String prefix = repo_prefix? repo_prefix: ""
    String registry = get_default_artifactory_registry()+"/"
    if (is_release_job()) {
        return registry+prefix+"release"
    }
    else{
        return registry+prefix+"develop"
    }
}

def get_default_artifactory_registry() {
    return "artifactory.netskope.io"
}

def get_pkgs_build_version() {
    if (is_release_job()) {
        return "${env.BUILD_TAG}.${BUILD_NUMBER}"
    }
    else{
        return "1.develop-${RELEASE}.${BUILD_NUMBER}"
    }
}

def get_imgs_build_version() {
    if (is_release_job()) {
        return "${env.BUILD_TAG}.${BUILD_NUMBER}"
    }
    else{
        return "${RELEASE}.${BUILD_NUMBER}"
    }
}

def get_branch_type(String branch) {
    println "Branch: " + branch
    def matcher = branch =~ /^(develop|Release)\d*/
    String branch_type
    if (matcher) {
        if (matcher.group(1) == "develop") {
            branch_type = "develop"
        } else if (matcher.group(1) == "Release") {
            branch_type = "release"
        }
    } else {
        branch_type = "feature"
    }
    println "Branch Type: " + branch_type
    return branch_type
}

def get_branch_name() {
    String branch_name = "develop"
    if(is_release_job()){
        branch_name = env.RELEASE_BRANCH
    }
    return branch_name
}

def get_agent(){
    String agent = "DEVELOP"
    if(is_release_job()){
        agent = "RELEASE"
    }
    return agent
}

def set_next_build_number(String job_name, String build_number) {
    println("set_next_build_number for "+ job_name+ " as "+ build_number)
    job = Jenkins.getInstance().getItemByFullName(job_name, hudson.model.Job.class)
    job.nextBuildNumber = Integer.valueOf(build_number)
    job.save()
}

@NonCPS
def update_polling(String job_name, String poll_expr, boolean shouldEnable){
    def jobObj = Jenkins.instance.getItemByFullName(job_name)
    job_found_msg = ""
    job_not_found_msg = ""
    if(jobObj!=null){
        if(shouldEnable){
            jobObj.addTrigger(new SCMTrigger(poll_expr))
            job_found_msg = "Enabled SCM Polling of [" + poll_expr + "] for job :<" + job_name +">"
            println(job_found_msg)
        }
        else{
            jobObj.setTriggers(new ArrayList())
            job_found_msg = "Disabled SCM Polling for job :<" + job_name +">"
            println(job_found_msg)
        }
    }
    else{
        job_not_found_msg = "Didn't find job :<" + job_name +">"
        println(job_not_found_msg)
    }
    return [
        job_found_msg,
        job_not_found_msg
    ]
}

def get_jobs_poling_status(job_found_msgs, job_not_found_msgs){
    found_msgs = job_found_msgs
    not_found_msgs = job_not_found_msgs
    found_msgs.removeAll(Arrays.asList(null,""))
    not_found_msgs.removeAll(Arrays.asList(null,""))
    msg_body = ""
    if(found_msgs.size()>0){
        msg_body = msg_body+ "\n\n Polling STATUS OF JOBS FOUND\n"
    }
    for(job_found in found_msgs){
        pos =job_found_msgs.indexOf(job_found) +1
        msg_body = msg_body + pos +". " + job_found + "\n"
    }
    if(not_found_msgs.size()>0){
        msg_body = msg_body+ "\n\n List OF JOBS didn't FOUND to Update SCM Polling\n"
    }
    for(job_not_found in not_found_msgs){
        pos =not_found_msgs.indexOf(job_not_found) +1
        msg_body = msg_body + pos+ ". " + job_not_found +"\n"
    }
    return msg_body
}


def is_email_publish_disabled(){
    if (env.DO_NOT_SEND_MAIL && env.DO_NOT_SEND_MAIL == "true") {
        println("Publishing Email is Disabled")
        return true
    }
    else{
        println("Publishing Email is Enabled")
        return false
    }
}

@NonCPS
def get_jira_ticket(String jobName, int buildNumber) {
    def job_obj = Jenkins.instance.getItemByFullName(jobName)
    def job_instance = job_obj.getBuildByNumber(buildNumber)
    def curr_node = job_instance.getExecution().getCurrentHeads().get(0)
    def curr_node_id = Integer.valueOf(curr_node.getId())
    def jira_ticket = null
    def log_msgs = []
    for (i = 0; i<=curr_node_id; i++) {
        def temp_node = job_instance.getExecution().getNode(Integer.valueOf(i).toString())
        if(temp_node ) {
            if(temp_node.getActions(org.jenkinsci.plugins.workflow.support.actions.LogStorageAction.class).size()>0) {
                def logactions = temp_node.getActions(org.jenkinsci.plugins.workflow.support.actions.LogStorageAction.class)
                StringWriter sw = new StringWriter();
                logactions.last().getLogText().writeLogTo(0, sw)
                def curr_log = sw.toString()
                def lines = curr_log.split("\n").toList()
                for (line in lines) {
                    if(line.contains("JIRA-TICKET:::")) {
                        jira_ticket = line.split("JIRA-TICKET:::")[-1]
                        break
                    }
                }
            }
        }
    }
    return jira_ticket
}

@NonCPS
def get_logs(String jobName, int buildNumber, int maxlines) {
    def job_obj = Jenkins.instance.getItemByFullName(jobName)
    def job_instance = job_obj.getBuildByNumber(buildNumber)
    def curr_node = job_instance.getExecution().getCurrentHeads().get(0)
    def curr_node_id = Integer.valueOf(curr_node.getId())
    def log_msgs = []
    for (i = 0; i<=curr_node_id; i++) {
        def temp_node = job_instance.getExecution().getNode(Integer.valueOf(i).toString())
        if(temp_node ) {
            if(temp_node.getActions(org.jenkinsci.plugins.workflow.support.actions.LogStorageAction.class).size()>0) {
                def logactions = temp_node.getActions(org.jenkinsci.plugins.workflow.support.actions.LogStorageAction.class)
                StringWriter sw = new StringWriter();
                logactions.last().getLogText().writeLogTo(0, sw)
                def curr_log = sw.toString()
                if(temp_node.getActions(org.jenkinsci.plugins.workflow.actions.ErrorAction.class).size()>0) {
                    def lines = curr_log.split("\n").toList()
                    def err = temp_node.getError().getError()
                    if(err.getCause()) {
                        lines.add(err.getCause())
                    }
                    if(err.getMessage()) {
                        lines.add(err.getMessage())
                    }
                    if(lines.size() >maxlines) {
                        lines = lines[[-maxlines..-1]]
                    }
                    lines.add("-----------------------------------------------")
                    lines.add("-----------------------------------------------")
                    lines.add("\n")
                    lines.add("\n")
                    log_msgs.addAll(lines)
                }
            }
        }
    }
    if(log_msgs.size()==0) {
        log_msgs.add("Couldn't find the error log.")
    }
    log_msgs = log_msgs.join("\n")
    return log_msgs
}

@NonCPS
def get_commit_string() {
    max_length = 100
    def commit_string = ""
    def change_log_sets = currentBuild.changeSets
    def num_messages = 0
    for (int i = 0; i < change_log_sets.size(); i++) {
        for (int j = 0; j < change_log_sets[i].items.length; j++) {
            num_messages += 1
            commit_msg = change_log_sets[i].items[j].msg.take(max_length)
            commit_string += " - ${commit_msg} \n"
        }
    }
    if (!commit_string) {
        commit_string = "No new changes"
    }
    return [commit_string, num_messages]
}

def send_email(String rcpt, String subject, String message){
    emailext body: "${message}",
    /*recipientProviders: [
     [$class: 'CulpritsRecipientProvider'],
     [$class: 'DevelopersRecipientProvider'],
     [$class: 'FirstFailingBuildSuspectsRecipientProvider']
     ],*/
    subject: subject,
    to: "${rcpt}"
}

def send_failure_msg(String message, String to_alias, String subject = "") {
    if (is_email_publish_disabled()) {
        return
    }
    rcpt = get_email_list(to_alias)
    result = currentBuild.result
    if (result == null){
        result = "FAILURE"
    }
    if ( subject.length() == 0 ) {
        subject = "Job \'${JOB_NAME}\' (${BUILD_NUMBER}) - ${result}"
    }
    String jobName = "${JOB_NAME}".toString()
    if(!jobName.contains("ut-service-develop-pipeline")) {
        int buildNum = "${BUILD_NUMBER}".toString().toInteger()
        errorLog = get_logs(jobName, buildNum, 50)// '${BUILD_LOG, maxLines=15}'
        message = message.replace("Thanks", "\n\t Error Log:\n${errorLog}\n\n\n    Thanks")
    }
    emailext body: "${message}",
    recipientProviders: [
        [$class: 'CulpritsRecipientProvider'],
        [$class: 'DevelopersRecipientProvider'],
        [$class: 'FirstFailingBuildSuspectsRecipientProvider']
    ],
    subject: subject,
    to: "${rcpt}"
}

def abort_build(String where, Exception e, boolean send_email=true) {
    println("Caught an exception"+ e.getClass().getSimpleName())
    println("currentBuild result"+ currentBuild.result)
    printStackTrace(e)
    if(!send_email){
        env.DO_NOT_SEND_MAIL = "true"
    }
    if(e.getClass().getSimpleName() == "PostBuildException"  && (currentBuild.result != "FAILURE" && currentBuild.result != "ABORTED")){
        currentBuild.result = 'SUCCESS'
        send_email_for_post_failures('''Hi,
            You are receiving this mail as the build is failing in post build actions  while`'''+ where +'''`. Please review the Build Logs at ${BUILD_URL}console
            Thanks,
            Productivity Engineering Team''', "developer-experience@netskope.com", "FAILED in function '"+where+"' while POST BUILD SUCCESS/ALWAYS ACTION")
    }
    else if(e.getClass().getSimpleName() == "SharedLibException"){
        mark_as_aborted(where)
        throw new org.jenkinsci.plugins.workflow.steps.FlowInterruptedException(
        hudson.model.Result.fromString('ABORTED') ,
        new jenkins.model.CauseOfInterruption.UserInterruption("developer-experience@netskope.com")
        )
    }
    else{
        currentBuild.result = 'FAILURE'
    }
}

def mark_as_aborted(String where){
    println("Aborting BUILD")
    currentBuild.result = 'ABORTED'
    send_email_for_fatal_errors('''Hi,
    You are receiving this mail as the build is ABORTED  while`'''+ where +'''`. Please review the Build Logs at ${BUILD_URL}console
    Thanks,
    Productivity Engineering Team''', "developer-experience@netskope.com", "ABORTED due to a failure in function '"+where+"'")
}

def send_email_for_post_failures(String message, String to_alias, String subject ) {
    try{
        if (is_email_publish_disabled()) {
            return
        }
        emailext body: "${message}",
        subject: 'Job \'${JOB_NAME}\' (${BUILD_NUMBER}) - '+subject,
        to: to_alias
    }
    catch(Exception e){
        println("Exception Occurred")
        printStackTrace(e)
    }
}

def send_email_for_fatal_errors(String message, String to_alias, String subject ) {
    try{
        if (is_email_publish_disabled()) {
            return
        }
        emailext body: "${message}",
        subject: 'Job \'${JOB_NAME}\' (${BUILD_NUMBER}) - '+ subject,
        to: to_alias
    }
    catch(Exception e){
        println("Exception Occurred")
        printStackTrace(e)
    }
}



def send_failure_msg(String emailrcpts = "developer-experience@netskope.com") {
    println("Default recipients ::" + emailrcpts)
    send_failure_msg("""Hi,

    You are receiving this mail as your commit may have broken the build. Please review the Build Logs at ${env.RUN_DISPLAY_URL}

    Thanks,
    Productivity Engineering Team""", emailrcpts)
}

def send_build_failure_msg(String emailrcpts = "developer-experience@netskope.com", org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper  error_classifier_build) {
    println("Default recipients ::" + emailrcpts)
    String email_subject_prefix = null
    if(error_classifier_build.resultIsBetterOrEqualTo("SUCCESS")){
        jira_ticket_details = get_jira_ticket(error_classifier_build.getProjectName(),  error_classifier_build.getNumber())
        email_subject_prefix = jira_ticket_details.split(";")[0]
        ticket_assignee = jira_ticket_details.split(";")[1]
        print("::::"+email_subject_prefix+":::")
    }
    def result = currentBuild.result
    if (result == null){
        result = "FAILURE"
    }
    String subject = "Job \'${JOB_NAME}\' (${BUILD_NUMBER}) - ${result}"
    if (email_subject_prefix) {
        subject = email_subject_prefix+ " ::: " + subject
    }
    send_failure_msg("""Hi,

    You are receiving this mail as your commit may have broken the build. Please review the Build Logs at ${env.RUN_DISPLAY_URL}

    Jira Ticket: https://netskope.atlassian.net/browse/${email_subject_prefix}
    ------------------------------------------------------------------------

    The ticket is currently assigned to ${ticket_assignee} for initial triage.
    If you are not the correct owner, please assign it to the appropriate owner.

    ------------------------------------------------------------------------

    Thanks,
    Productivity Engineering Team""", emailrcpts, subject)
}

def is_slack_notification_disabled(){
    if (env.DO_NOT_SEND_SLACK_MSG && env.DO_NOT_SEND_SLACK_MSG == "true") {
        println("Sending Slack Notification is Disabled")
        return true
    }
    else{
        println("Sending Slack Notification is Enabled")
        return false
    }
}

def send_release_failures_to_slack() {
    send_slack_msg("release-builds", null, null)
}

def send_slack_msg(String channel_name , String simple_msg = null, Map custom_msg = null) {
    if(is_slack_notification_disabled()) {
        return
    }
    ch_name = channel_name.startsWith("#")? channel_name : "#"+channel_name
    println("Sending Notification to "+ ch_name)
    def contributors  =  get_email_list("developer-experience@netskope.com")
    String build_result = currentBuild.result
    kv_msg = null
    sl_msg  = null
    slackMessage = """
    @here
    *Job:*`(${JOB_NAME} #${BUILD_NUMBER})`
    *Contributors & Watchers:* `${contributors}`
    *Result:*  `${build_result}`
    (<${env.RUN_DISPLAY_URL}|View Logs>)"""
    if(custom_msg) {
        kv_msg = slackMessage + "\n"
        custom_msg.each {
            kv_msg = kv_msg + "\n\t*" + it.key + ":* `" + it.value + "`"
        };
        slackMessage = kv_msg
    }
    if(simple_msg) {
        slackMessage = slackMessage + "\n```${simple_msg}```"
    }
    println("Slack Message "+ slackMessage)
    slackSend color : '#d94c3a', channel: ch_name, message:slackMessage
}

def send_slack_msg_with_jira(String channel_name, def error_classifier_build) {
    def custom_msg = [:]
    try {
        if(error_classifier_build.resultIsBetterOrEqualTo("SUCCESS")){
            jira_ticket_details = get_jira_ticket(error_classifier_build.getProjectName(),  error_classifier_build.getNumber())
            email_subject_prefix = jira_ticket_details.split(";")[0]
            custom_msg["Jira Ticket"] = "<https://netskope.atlassian.net/browse/" + email_subject_prefix + "|" + email_subject_prefix + ">"
            custom_msg["Ticket Assignee"] = jira_ticket_details.split(";")[1]
        }
    } catch(Exception e) {
        println("Failed to fetch the Jira ticket details")
    }
    send_slack_msg(channel_name, null, custom_msg)
}

def eval_branch_name(branch_name){
    g_branch_name = branch_name
    String hf56_locator = new String("hotfix-pipeline")
    if(currentBuild.getFullProjectName().endsWith(hf56_locator)){
        String b_tag = params.BUILD_TAG.toString()
        if(b_tag.startsWith("56")){
            return "release/${branch_name}"
        }
    }
    return g_branch_name
}

// In the case of reusing a build machine that had submodules checked out from previous run.
// we must clear the submodules first to ensure there's no discrepancy, i.e.
// previous run has "pinned" commit for a particular submodule, but this run
// has specified branch per .gitmodules.  In this case it'll say "already exists and is not an empty directory"
// Note: noop if .gitmodules is not found.
def clean_submodule_dirs() {
    def map = get_submodule_map(true)
    try {
        map.each{ name, l ->
            def p = l[0]
            sh "sudo rm -rf " + p
        }

        // Must ensure dataplane/src does not exist.  It's possible from previous run, .gitmodules
        // does not exist but dataplane/src exists.
        sh "rm -rf dataplane/src"
    } catch (Exception e) {
        println("Failed to clean submodules")
        println(e)
    }
}

def clean_webui_dir() {
    try {
        println("clean webui directory:\n")
        sh "rm -rf components/webui"
    } catch (Exception e) {
        println("Failed to clean webui directory")
        println(e)
    }
}

def clean_dataplane_dir() {
    try {
        println("clean dataplane directory:\n")
        sh "rm -rf dataplane/src"
    } catch (Exception e) {
        println("Failed to clean dataplane directory")
        println(e)
    }
}

def handle_submodules(String repo_location = '', String cred_id = null) {
    def map = get_submodule_map()
    def is_http = repo_location.startsWith("http")
    try {
        map.each{ name, section ->
            def path = section[0]
            def branch = section[1]
            def url = section[2]
            if (is_http && cred_id != null) {
                withCredentials([
                    usernamePassword(credentialsId: cred_id, passwordVariable: 'GITHUB_PASS', usernameVariable: 'GITHUB_USER')
                ]) {
                    // If git_check_out (caller of this function) uses http[s] for the overall repo, then we
                    // have to force `git submodules` to use https also so we can pass in username/password,
                    // as ssh keys may not be setup on the builder machine executing this pipeline.
                    url = url.replace("git@github.com:", "https://$GITHUB_USER:$GITHUB_PASS@github.com/")
                    sh "git submodule set-url " + path + ' "' + url + '"'  // Updates .gitsubmodules
                }
            }
            sh "git submodule sync"  // Updates git config with .gitsubmodules
            println("Updating '" + path + "' from remote branch: " + branch)
            sh "rm -rf " + path + "; git submodule update --init " + path + "; git submodule update --remote " + path
            sh "git submodule status " + path
        }
    } catch (Exception e) {
        println("Failed to handle submodules")
        println(e)
    }
}

def get_submodule_map(boolean get_all=false) {
    def map = [:]
    def exists = fileExists '.gitmodules'
    if (!exists) {
        println(".gitmodules not found")
        return map
    }
    content = readFile '.gitmodules'
    println("get_submodule_map found .gitmodules:\n" + content + ":")
    try {
        def path, branch, section, url;
        content.split('\n').each { line ->
            if (line != null) {
                line = line.trim()
                if (line.startsWith("[submodule ")) {
                    if ((get_all && path != null) || branch != null) {
                        map.put(section, [path, branch, url])
                    } else if (section != null) {
                        print(section + " has no branch and the pinned version is already fetched by Jenkins. Moving on.")
                    }
                    branch = null
                    path = null
                    url = null
                    section = line.split(" ")[-1].replace("]", '')
                } else if (line.find("=") != -1) {
                    if (line.startsWith("path")) {
                        path = line.split("=")[1].trim()
                    } else if (line.startsWith("branch")) {
                        branch = line.split("=")[1].trim()
                    } else if (line.startsWith("url")) {
                        url = line.split("=")[1].trim()
                    }
                }
            }
        }

        if ((get_all && path != null) || branch != null) {
            map.put(section, [path, branch, url])
        } else if (section != null) {
            print(section + " has no branch and the pinned version is already fetched by Jenkins. Moving on.")
        }
    } catch (Exception e) {
        println("Failed to get_submodule_map")
        println(e)
    }
    return map
}

def unsilence_pip() {
    sh "sudo -H pip config unset global.silent --global"
}

def silence_pip() {
    sh "sudo -H pip config set global.silent 1 --global"
}

// pass in refspec = "+refs/heads/${BRANCH}:refs/remotes/origin/${BRANCH}" to
// save clone/checkout time.  This can shave minutes off service repo clone/checkout times
def git_check_out(String url, String branch_name, boolean tags=true, boolean shallow=false, int timeout=60, boolean submodules = false, String credentialsId = null, boolean send_email = true, boolean commit_sha = false, String refspec = null) {
    def git_options = null
    try {
        int depth=0;
        if(shallow==true) {
            depth=1
        }
        def git_user_remote_confg = [:]
        if(credentialsId) {
            git_user_remote_confg.put("credentialsId",credentialsId)
        }
        if(refspec) {
            git_user_remote_confg.put("refspec", refspec)
        }
        println("Git URL: ${url}")
        if(isUnix()) {
            def platform = sh(returnStdout: true, script:'uname -s')
            def curr_dir = sh(returnStdout: true, script:'pwd')
            curr_dir = curr_dir.split()[0]
            platform = platform.split() as List
            print(platform)
            if (platform[0] != "Darwin") {
                try {
                    sh "sudo -n chown -R $USER:$USER ${curr_dir}"
                } catch( Exception perm_exec) {
                    print("Failed to change permissions")
                }
            }
        }

        def g_branch_name= null
        if (commit_sha) {
            g_branch_name = branch_name
        }
        else {
            g_branch_name = "origin/${branch_name}"
        }
        git_user_remote_confg.put("url","${url}")
        println("Repository remote config "+ git_user_remote_confg)
        git_options = [
            $class: 'GitSCM',
            branches: [[name: "${g_branch_name}"]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [
                [$class: 'CleanBeforeCheckout'],
                [$class: 'CheckoutOption', timeout:timeout],
                [$class: 'CloneOption', depth: depth, noTags: tags, reference: '', shallow: shallow, timeout: timeout, honorRefspec: true],
                [$class: 'AuthorInChangelog']
            ],
            submoduleCfg: [],
            userRemoteConfigs: [git_user_remote_confg]
        ]
        if(submodules) {
            git_options["extensions"].add(
                    [   $class: 'SubmoduleOption',
                        disableSubmodules: false,
                        parentCredentials: true,
                        recursiveSubmodules: true,
                        reference: '',
                        trackingSubmodules: false
                    ]
                    )
        }
        clean_submodule_dirs()
        print(git_options)
        checkout(git_options)
        if (submodules) handle_submodules(url, credentialsId)
    } catch(Exception e) {
        deleteDir()
        try {
            checkout(git_options)
            if (submodules) handle_submodules(url, credentialsId)
            send_email= false
        } catch(Exception exception) {
            abort_build("git_check_out", new SharedLibException(exception))
            println("Exception Occurred in GIT Checkout"+ exception.getMessage())
        }
    }
}

def git_check_out_pre_build_merge(String url, String branch_name, String target_branch_name, boolean tags=true, boolean shallow=false, int timeout=60, boolean submodules = false, String credentialsId = null, boolean send_email = true) {
    try {
        int depth=0;
        if(shallow==true) {
            depth=1
        }
        def git_user_remote_confg = [:]
        if(credentialsId) {
            git_user_remote_confg.put("credentialsId",credentialsId)
        }
        println("Git URL: ${url}")
        g_branch_name = branch_name
        git_user_remote_confg.put("url","${url}")
        println("Repository with config "+ git_user_remote_confg)
        def git_options = [
            $class: 'GitSCM',
            branches: [
                [name: "origin/${g_branch_name}"]
            ],
            doGenerateSubmoduleConfigurations: false,
            extensions: [
                [$class: 'CleanBeforeCheckout'],
                [$class: 'CheckoutOption', timeout:timeout],
                [$class: 'CloneOption', depth: depth, noTags: tags, reference: '', shallow: shallow, timeout: timeout],
                [$class: 'AuthorInChangelog'],
                [$class: 'PreBuildMerge', options: [mergeRemote: "origin", mergeTarget: target_branch_name]]
            ],
            submoduleCfg: [],
            userRemoteConfigs: [git_user_remote_confg]
        ]
        if(submodules) {
            git_options["extensions"].add(
                    [   $class: 'SubmoduleOption',
                        disableSubmodules: false,
                        parentCredentials: true,
                        recursiveSubmodules: true,
                        reference: '',
                        trackingSubmodules: true
                    ]
                    )
        }
        checkout(git_options)
    } catch(Exception e) {
        abort_build("git_check_out", new SharedLibException(e))
        println("Exception Occurred in GIT Checkout")
    }
}

def git_checkout_tag(String url, String tag_name, boolean tags=false, boolean shallow=false, int timeout=60, String credentialsId = null) {
    try {
        int depth=0;
        if(shallow==true) {
            depth=1
        }
        println("Git URL: ${url}")
        repo = url.tokenize('/').last().split("\\.")[0]
        println("Repository Name: ${repo}")
        def git_user_remote_confg = [:]
        if(credentialsId) {
            git_user_remote_confg.put("credentialsId",credentialsId)
        }
        git_user_remote_confg.put("refspec", '+refs/tags/*:refs/remotes/origin/tags/*')
        git_user_remote_confg.put("url", "${url}")

        checkout([
            $class: 'GitSCM',
            branches: [
                [name: "origin/tags/${tag_name}"]
            ],
            doGenerateSubmoduleConfigurations: false,
            extensions: [
                [$class: 'CleanBeforeCheckout'],
                [$class: 'CheckoutOption', timeout: timeout],
                [$class: 'CloneOption', depth: depth, noTags: tags, reference: '', shallow: shallow, timeout: timeout]
            ],
            submoduleCfg: [],
            userRemoteConfigs: [git_user_remote_confg]
        ])
    } catch(Exception e) {
        abort_build("git_checkout_tag", new SharedLibException(e))
    }
}

def git_check_out_wo_clean(String url, String branch_name, boolean tags=false, boolean shallow=false, int timeout=60) {
    try {
        int depth=0;
        if(shallow==true) {
            depth=1
        }
        println("Git URL: ${url}")
        g_branch_name = branch_name
        checkout([
            $class: 'GitSCM',
            branches: [
                [name: "origin/${g_branch_name}"]
            ],
            doGenerateSubmoduleConfigurations: false,
            extensions: [
                [$class: 'CheckoutOption', timeout:timeout],
                [$class: 'CloneOption', depth: depth, noTags: tags, reference: '', shallow: shallow, timeout: timeout]
            ],
            submoduleCfg: [],
            userRemoteConfigs: [[url: "${url}"]]
        ])
    } catch(Exception e) {
        abort_build("git_check_out_wo_clean", new SharedLibException(e))
    }
}


def remove_packages(String path = ".", boolean isPostAction = false) {
    try {
        sh "cd ${path} && rm -f *.deb || true"
    } catch(Exception e) {
        if(isPostAction){
            abort_build("remove_packages", new PostBuildException(e), false)
        }else{
            abort_build("remove_packages", new SharedLibException(e))
        }
    }
}


def remove_old_builds(String path = ".", boolean isPostAction = false) {
    try {
        sh "find ${path} -mtime +2 -exec rm {} \\;"
    } catch(Exception e) {
        if(isPostAction){
            abort_build("remove_old_builds", new PostBuildException(e), false)
        }else{
            abort_build("remove_old_builds", new SharedLibException(e))
        }
    }
}

def remove_old_builds_windows(boolean isPostAction = false) {
    try {
        bat 'FORFILES /S /D -30 /C "cmd /c del @path"'
    } catch(Exception e) {
        if(isPostAction){
            abort_build("remove_old_builds_windows", new PostBuildException(e), false)
        }else{
            abort_build("remove_old_builds_windows", new SharedLibException(e))
        }
    }
}

def remove_old_builds_mac_ios(boolean isPostAction = false) {
    try {
        sh '''
          find . -mtime +30 -exec rm {} \\; || true
          find . -type d -empty -exec rmdir {} \\; || true
      '''
    } catch(Exception e) {
        if(isPostAction){
            abort_build("remove_old_builds_mac_ios", new PostBuildException(e), false)
        }else{
            abort_build("remove_old_builds_mac_ios", new SharedLibException(e))
        }
    }
}

def remove_pip_cache_lock_file() {
    try {
        sh 'rm -rf /home/nsadmin/.cache/pip/selfcheck.json.lock'
    } catch(Exception e) {
        println("Exception Occurred")
    }
}

def release_version() {
    try{
        return "55.0.0"
    }
    catch(Exception e){
        abort_build("release_version", new SharedLibException(e))
    }
}

def get_release_ver(){
    if(env.RELEASE){
        return  env.RELEASE
    }else{
        return env.BUILD_TAG
    }
}

def get_git_release_ver(){
    if(env.RELEASE_BRANCH){
        return  env.RELEASE_BRANCH
    }else{
        return env.RELEASE
    }
}

def fail(boolean isPostAction = false) {
    try{
        throw new Exception("Dummy Failure")
    }
    catch(Exception e){
        if(isPostAction){
            abort_build("fail", new PostBuildException(e))
        }else{
            abort_build("fail", new SharedLibException(e))
        }
    }
}

def printStackTrace(Exception e) {
    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    String exceptionAsString = sw.toString();
    println(exceptionAsString)
}

def get_version(String channel) {
    println("get_version for "+ channel)
    String release_ver = get_release_ver()
    try{
        switch(channel.toLowerCase()){
            case "release":
                return "${release_ver}.${BUILD_NUMBER}"
            case "production":
                return "${release_ver}"
            case "develop":
                return "1.develop-${release_ver}.${BUILD_NUMBER}"
        }
    }
    catch(Exception e){
        abort_build("get_version(" + channel + ")", new SharedLibException(e))
    }
}

def get_git_branch_name(String gitURL, String channel) {
    def sout = new StringBuilder(), serr = new StringBuilder()
    def command = "git ls-remote --heads "+gitURL
    print(command)

    def proc = command.execute()
    proc.consumeProcessOutput(sout, serr)
    proc.waitForOrKill(15000)
    // println(sout.toString()) Uncomment while debugging
    def branchList = sout.toString().replaceAll("(.*?)refs/heads/", "").split("\n").toList()
    // println(branchList)  Uncomment while debugging
    def branch = "master"
    String branch_release = get_git_release_ver()
    def brnch_release = "${branch_release}"
    def brnch_hotfix = "hot-fix/${branch_release}"
    def brnch_production = "${branch_release}"
    def brnch_develop = "develop"

    switch(channel.toLowerCase()){
        case "release":
            if(branchList.contains(brnch_release)){
                branch = brnch_release
            }
            break;
        case "production":
            if(branchList.contains(brnch_production)){
                branch = brnch_production
            }
            break;
        case "develop":
            if(branchList.contains(brnch_develop)){
                branch = brnch_develop
            }
            break;
        case "hotfix":
            if(branchList.contains(brnch_hotfix)){
                branch = brnch_hotfix
            }
            break;
    }
    println("Git Branch used in the job "+ branch)
    return branch
}

def get_git_branch_name_by_channel(String channel) {
    def branch = ""
    String branch_release = get_git_release_ver()
    def brnch_release = "${branch_release}"
    def brnch_production = "${branch_release}"
    def brnch_develop = "develop"

    switch(channel.toLowerCase()){
        case "release":
            branch =  brnch_release
            break
        case "production":
            branch =  brnch_production
            break
        case "develop":
            branch =  brnch_develop
            break
    }
    println("Git Branch used in the job "+ branch)
    return branch
}

def git_checkout_by_channel(String url, String channel, boolean branchMaster = false, boolean tags=false, boolean shallow=false, int timeout=60) {
    def branch = get_git_branch_name_by_channel(channel)
    if(branchMaster== true){
        branch = "master"
    }
    switch(channel.toLowerCase()){
        case "release":
            git_check_out(url, branch, tags, shallow, timeout)
            break
        case "production":
            git_checkout_tag(url, get_git_release_ver(), tags, shallow, timeout)
            break
        case "develop":
            git_check_out(url, branch, tags, shallow, timeout)
            break
    }
}

def do_docker_login(def registry) {
    withCredentials([
        usernamePassword(credentialsId: 'ARTIFACTORY_DOCKER_LOGIN', passwordVariable: 'ART_PASS', usernameVariable: 'ART_USER')
    ]) {
        withEnv(["REGISTRY=${registry}"]) {
            sh '''
            set +x
            docker login -u $ART_USER -p "$ART_PASS" $REGISTRY
            set -x

        '''
        }
    }
}

def push_latest_images(def images_map, def registry, def images_bucket_size = 10, String custom_tag = "latest") {
    do_docker_login(registry)
    def queue = [:]
    def images_queue = []
    (images_map.keySet() as List).collate(images_bucket_size).each{
        images_queue.add(images_map.subMap(it))
    }
    def latest_tag = is_fedramp_job() ? custom_tag : "latest"
    println(images_queue)
    for(item in images_queue) {
        def stg_name = "Latest Images Queue :"+ images_queue.indexOf(item)
        def target_images = item
        queue[stg_name] = {
            for(image_name in target_images.keySet()) {
                def img_version = target_images[image_name]
                def name = image_name
                sh """
                docker tag ${name}:${img_version} ${name}:${latest_tag}
                docker push ${name}:${latest_tag}
                """
            }
        }
    }
    parallel queue
}

def push_latest_images_for_multi_node(def version, def registry, def images_bucket_size = 10) {
    do_docker_login(registry)
    def images_map=  get_target_images("${version}")
    def queue = [:]
    def images_queue = []
    (images_map.keySet() as List).collate(images_bucket_size).each{
        images_queue.add(images_map.subMap(it))
    }
    def latest_tag = "latest"
    println(images_queue)
    for(item in images_queue) {
        def stg_name = "${env.STAGE_NAME} Latest Images Queue :"+ images_queue.indexOf(item)
        def target_images = item
        queue[stg_name] = {
            for(image_name in target_images.keySet()) {
                def img_version = target_images[image_name]
                def name = image_name
                if(sh(script: "docker images -q ${name}:${img_version}", returnStdout: true).trim().isEmpty()) {
                    continue
                }
                sh """
                docker tag ${name}:${img_version} ${name}:${latest_tag}
                docker push ${name}:${latest_tag}
                """
            }
        }
    }
    parallel queue
}


def push_latest_images_jfrog_cli(def images_queue, def build_map) {
    def latest_tag = "latest"
    def server_id = "${RTF_SRC}"
    def rtf_url = get_rtf_url(server_id)
    println(images_queue)
    def queue = [:]
    for(item in images_queue) {
        def stg_name = "${env.STAGE_NAME} Latest Images Queue :"+ images_queue.indexOf(item)
        def target_images = item
        queue[stg_name] = {
            for(image_name in target_images.keySet()) {
                def img_version = target_images[image_name]
                def name = image_name
                if(sh(script: "docker images -q ${name}:${img_version}", returnStdout: true).trim().isEmpty()) {
                    continue
                }
                sh "docker tag ${name}:${img_version} ${name}:${latest_tag}"
                withCredentials([
                    usernamePassword(credentialsId: 'ARTIFACTORY_DOCKER_LOGIN', passwordVariable: 'ART_PASS', usernameVariable: 'ART_USER')
                ]) {
                    sh "jf rt dp " + name + ":" + latest_tag + " " + build_map['repo_name'] + " --url "+  rtf_url +" --user ${ART_USER} --password ${ART_PASS}"
                }
            }
        }
    }
    parallel queue
}

def publish_consolidated_build_info(def server_id, def build_name, def build_number, def build_info) {
    def json_str = JsonOutput.toJson(build_info)
    json_str = JsonOutput.prettyPrint(json_str)
    writeJSON file: 'main_build.json', json: json_str, pretty: 4
    def rtf_url = get_rtf_url(server_id)
    sh "jf rt curl -X PUT -H 'Content-type: application/json' -T main_build.json --server-id ${server_id} /api/build"
}

def initialize_build_info(def build_name, def build_number) {
    def initial_build_info = [:]
    data = get_build_git_data(build_name, build_number.toInteger())
    def env_cmd = ""
    for (item in data) {
        env_cmd = env_cmd +  " export ${item}=${data[item]} "
    }
    sh "jf rt bce " + build_name + " " + build_number
    sh "jf rt bp " + build_name + " " + build_number + " --dry-run > build.json"
    def json_string = sh(returnStdout:true, script: "cat build.json").trim()
    initial_build_info = readJSON text: json_string
    initial_build_info.put("url", "${BUILD_URL}".toString())
    return initial_build_info
}

def append_build_info(def build_name, def build_number, def build_info) {
    sh "jf rt bp " + build_name + " " + build_number + " --dry-run"
    sh "jf rt bp " + build_name + " " + build_number + " --dry-run > build.json"
    def json_string = sh(returnStdout:true, script: "cat build.json").trim()
    image_map = readJSON text: json_string
    build_info.put("modules", build_info["modules"] + image_map["modules"])
}

def push_multinode_images_jfrog_cli(def images_map, def build_map, def total_build_images, def build_info) {
    def repo_name = build_map['repo_name']
    def build_name = build_map['build_name']
    def build_number = build_map['build_number']
    def no_of_threads = build_map['no_of_threads']
    def push_latest = build_map['push_latest']
    def server_id = "${RTF_SRC}"
    def rtf_url = get_rtf_url(server_id)

    def images_queue = []
    def queue = [:]
    no_of_images = images_map.keySet().size()
    images_bucket_size = Math.round((no_of_images/no_of_threads)).toInteger()
    if ( images_bucket_size == 0 && no_of_images/no_of_threads > 0) {
        images_bucket_size = 1
    }

    println "Total number of images to push: " + no_of_images
    println "Total number of threads: " + no_of_threads
    println "Size of image bucket: " + images_bucket_size
    // When image bucket size zero
    (images_map.keySet() as List).collate(images_bucket_size).each{
        images_queue.add(images_map.subMap(it))
    }
    println "Total number of image buckets: " + images_queue.size()
    println(images_queue)

    for(item in images_queue) {
        def stg_name = "Queue :"+ images_queue.indexOf(item)
        def target_images = item
        queue[stg_name] = {
            for(image_name in target_images.keySet()) {
                def img_version = images_map[image_name]
                total_build_images.put(image_name, img_version)
                withCredentials([
                    usernamePassword(credentialsId: 'ARTIFACTORY_DOCKER_LOGIN', passwordVariable: 'ART_PASS', usernameVariable: 'ART_USER')
                ]) {
                    sh "jf config show; hostname; export JFROG_CLI_LOG_LEVEL=DEBUG; jf rt dp " + image_name + ":" + img_version + " " + repo_name + " --build-name=" + build_name + " --build-number=" + build_number + " --url "+  rtf_url +" --user ${ART_USER} --password ${ART_PASS}"
                }
            }
        }
    }
    parallel queue

    append_build_info(build_name, build_number, build_info)
    if(push_latest) {
        build_map = ['repo_name': repo_name, 'build_name': build_name, 'build_number': build_number, 'rtf_url': rtf_url]
        push_latest_images_jfrog_cli(images_queue, build_map)
    }
}

def push_images_jfrog_cli(def images_map, def buildInfo, def rtf_url, def repo_name, def build_name = "${JOB_NAME}", def build_number = "${BUILD_NUMBER}", def no_of_threads = 5) {
    def images_queue = []
    def queue = [:]
    def server_id = "${RTF_SRC}"
    no_of_images = images_map.keySet().size()
    images_bucket_size = Math.round((no_of_images/no_of_threads)).toInteger()
    if ( images_bucket_size == 0 && no_of_images/no_of_threads > 0) {
        images_bucket_size = 1
    }
    println "Total number of images to push: " + no_of_images
    println "Total number of threads: " + no_of_threads
    println "Size of image bucket: " + images_bucket_size
    // When image bucket size zero
    (images_map.keySet() as List).collate(images_bucket_size).each{
        images_queue.add(images_map.subMap(it))
    }
    println "Total number of image buckets: " + images_queue.size()
    println(images_queue)

    // get git commit info
    data = get_build_git_data("${JOB_NAME}", "${BUILD_NUMBER}".toString().toInteger())
    def env_cmd = ""
    for (item in data) {
        env_cmd = env_cmd +  " export ${item}=${data[item]} "
    }
    sh "${env_cmd}; jf rt bce " + build_name + " " + build_number
    for(item in images_queue) {
        def stg_name = "Queue :"+ images_queue.indexOf(item)
        def target_images = item
        queue[stg_name] = {
            for(image_name in target_images.keySet()) {
                def img_version = images_map[image_name]
                sh "jf rt dp " + image_name + ":" + img_version +  " " + repo_name + " --build-name=" + build_name + " --build-number=" + build_number + " --server-id "+ server_id
            }
        }
    }
    parallel queue
    sh "jf rt bp " + build_name + " " + build_number + " --server-id "+ server_id
}

def push_images(def images_map, def buildInfo, def server_id,  def keep_count, def repo_name, def build_name = "${JOB_NAME}", def build_number = "${BUILD_NUMBER}", def retention = true, def images_bucket_size = 5) {
    def server = Artifactory.server "${server_id}"
    def rtDocker = Artifactory.docker server: server
    // Removed the applying retention from this function.
    // However the function arguments references related to retention cannot be removed immediately
    // There are 14 pipelines using this function. will update the pipelines later.
    collectEnv(buildInfo.getEnv())
    update_git_data(buildInfo, "${JOB_NAME}", "${BUILD_NUMBER}".toString().toInteger())
    def queue = [:]
    def images_queue = []
    (images_map.keySet() as List).collate(images_bucket_size).each{
        images_queue.add(images_map.subMap(it))
    }
    println(images_queue)
    for(item in images_queue) {
        def stg_name = "Queue :"+ images_queue.indexOf(item)
        def target_images = item
        println(stg_name)
        queue[stg_name] = {
            for(image_name in target_images.keySet()) {
                def img_version = images_map[image_name]
                def retry_counter = 1
                retry(3) {
                    try {
                        rtDocker.push("${image_name}:${img_version}", repo_name, buildInfo)
                    } catch( Exception exec) {
                        sleep(time:retry_counter*15,unit:"SECONDS")
                        println("Exception occurred during docker push"+ exec)
                        throw exec
                    }
                }
            }
        }
    }
    parallel queue
    def updated_modules = []
    def modules = buildInfo.getModules()
    for( module in modules) {
        def artifacts = module.getArtifacts()
        def artifact_names = []
        def updated_artifacts = []
        for (artifact in artifacts) {
            if(artifact.getName() in artifact_names) {
                continue
            }
            artifact_names.add(artifact.getName())
            updated_artifacts.add(artifact)
        }
        module.setArtifacts(updated_artifacts)
    }
    buildInfo.setModules(modules)
    server.publishBuildInfo buildInfo
}

def push_images_for_multinode_builds(def server_id, def repo_name, def no_of_threads = 5, def push_latest = true, def total_build_images, def build_info) {
    def server = Artifactory.server "${server_id}"
    def images_map = [:]
    images_map = get_target_images("${VERSION}")
    images_map = images_map.findAll {
        it.key.startsWith("${REGISTRY}")
    }
    print("From ${env.STAGE_NAME} stage stork images to be published are ${images_map}")
    rtf_url = get_rtf_url(server_id)

    def build_map = ['rtf_url': rtf_url, 'repo_name': repo_name, 'build_name': "${JOB_NAME}", 'build_number': "${BUILD_NUMBER}", 'no_of_threads': 5, 'push_latest': true]
    push_multinode_images_jfrog_cli(images_map, build_map, total_build_images, build_info)
    print("Artifactory push completed for the images built from ${env.STAGE_NAME} stage.")
}

def update_docker_build_modules(def buildInfo) {
    print("Update the build info with docker build modules")
    def updated_modules = []
    def modules = buildInfo.getModules()
    for( module in modules) {
        def artifacts = module.getArtifacts()
        def artifact_names = []
        def updated_artifacts = []
        for (artifact in artifacts) {
            if(artifact.getName() in artifact_names) {
                continue
            }
            artifact_names.add(artifact.getName())
            updated_artifacts.add(artifact)
        }
        module.setArtifacts(updated_artifacts)
    }
    currentBuild.description = "Total Images uploaded are "+ modules.size()
    buildInfo.setModules(modules)
}

def publish_docker_build_info(def artifactory_server_id, def artifactory_build_info) {
    print("Publish the docker build info to ${artifactory_server_id} artifactory server.")
    def server = Artifactory.server "${artifactory_server_id}"
    server.publishBuildInfo artifactory_build_info
}

def push_release_images(def images_map, def buildInfo, def server_id, def repo_name, def build_name = "${JOB_NAME}", def build_number = "${BUILD_NUMBER}", def images_bucket_size = 5) {
    def server = Artifactory.server "${server_id}"
    def rtDocker = Artifactory.docker server: server

    // Not applying retention for release builds wrt JIRA EP-12208
    collectEnv(buildInfo.getEnv())
    update_git_data(buildInfo, "${JOB_NAME}", "${BUILD_NUMBER}".toString().toInteger())

    def queue = [:]
    def images_queue = []
    (images_map.keySet() as List).collate(images_bucket_size).each{
        images_queue.add(images_map.subMap(it))
    }
    println(images_queue)
    for(item in images_queue) {
        def stg_name = "Queue :"+ images_queue.indexOf(item)
        def target_images = item
        println(stg_name)
        queue[stg_name] = {
            for(image_name in target_images.keySet()) {
                def img_version = images_map[image_name]
                def retry_counter = 1
                retry(3) {
                    try {
                        rtDocker.push("${image_name}:${img_version}", repo_name, buildInfo)
                    } catch( Exception exec) {
                        sleep(time:retry_counter*15,unit:"SECONDS")
                        println("Exception occurred during docker push"+ exec)
                        throw exec
                    }
                }
            }
        }
    }
    parallel queue
    def updated_modules = []
    def modules = buildInfo.getModules()
    for( module in modules) {
        def artifacts = module.getArtifacts()
        def artifact_names = []
        def updated_artifacts = []
        for (artifact in artifacts) {
            if(artifact.getName() in artifact_names) {
                continue
            }
            artifact_names.add(artifact.getName())
            updated_artifacts.add(artifact)
        }
        module.setArtifacts(updated_artifacts)
    }
    buildInfo.setModules(modules)
    server.publishBuildInfo buildInfo
}

def get_target_images(def target_version, boolean reverse = true) {
    def images = sh(returnStdout: true, script:'docker images --format "{{.ID}}:->{{.Repository}}:->{{.Tag}}"')
    println("From ${env.STAGE_NAME} : List of docker images available are ${images}")
    images = images.split("\n")
    def found_images = [:]
    if(reverse) {
        images = images.reverse()
    }
    for (image in images) {
        def parts = image.split(":->")
        def image_id = parts[0]
        def repo_qual_name = parts[1]
        def version = parts[2]
        if(version == target_version) {
            println("From ${env.STAGE_NAME} image found is : ${repo_qual_name}:${version}")
            found_images[repo_qual_name] = version
        }
    }
    return found_images
}

def remove_images(String version) {
    def images = sh(returnStdout: true, script:'docker images --format "{{.ID}}:->{{.Repository}}:->{{.Tag}}" | grep "${version}"')
    for (image in images.split("\n").reverse()) {
        def parts = image.split(":->")
        if(parts[2] == "${version}") {
            IMAGE_ID = parts[0]
            REPOSITORY = parts[1]
            try {
                sh """
                docker rmi ${REPOSITORY}:${VERSION}
                """
            } catch(Exception e ) {
                println("Failed while cleaning up images")
            }
        }
    }
}

def publish_docker_digest_to_artifactory(def repo_name, def ver, def dir_sha, def web_path ) {
    def digest_sha = sh(returnStdout: true, script: " docker inspect  ${repo_name}:${ver} --format='{{.RepoDigests}}'")
    outFile = "${repo_name}".split("/")[-1]+ ".txt"
    target_url = "${web_path}/${outFile}"
    outFilepath  = "${dir_sha}/${outFile}"
    sh """
        rm -rf ${dir_sha}
        mkdir -p ${dir_sha}
        touch ${outFilepath}
    """
    writeFile(file : "${outFilepath}", text : digest_sha)
    client_shasum = sh(returnStdout: true, script: "shasum ${outFilepath}").split(" ")[0]
    client_md5= sh(returnStdout: true, script: "md5sum ${outFilepath}").split(" ")[0]
    withCredentials([
        usernamePassword(credentialsId: 'ARTIFACTORY_DOCKER_LOGIN', passwordVariable: 'ART_PASS', usernameVariable: 'ART_USER')
    ]) {
        httpRequest(
                consoleLogResponseBody: true,
                customHeaders: [
                    [maskValue: true, name: 'Authorization', value: "Basic "+ "${ART_USER}:${ART_PASS}".bytes.encodeBase64().toString()],
                    [maskValue: false, name: 'X-Checksum-MD5', value: client_md5],
                    [maskValue: false, name: 'X-Checksum-Sha1', value: client_shasum]
                ],
                httpMode: 'PUT',
                ignoreSslErrors: true,
                requestBody: digest_sha,
                url: target_url,
                )
    }
}

def get_build_git_data(String job_name, Integer build_num) {
    try {
        def job = Jenkins.instance.getItemByFullName(job_name)
        print("get_build_git_data from job: ${job_name} # ${build_num}" )
        def job_instance = job.getBuildByNumber(build_num)
        def actions = job_instance.getActions(hudson.plugins.git.util.BuildData)
        git_build_map = [:]
        for (action in actions) {
            for(remote in action.getRemoteUrls() ){
                if(remote.toLowerCase().contains("jenkins-pipeline")) continue;
                git_build_map["GIT_COMMIT_SHA_FOR_"+remote.split("/")[-1].replace(".git", "")] = action.getLastBuiltRevision().getSha1().name()
            }
        }
    } catch (Exception e) {
        println(e.toString())
        println(e.getMessage());
        throw e
    }
    return git_build_map
}

def update_git_data(def buildInfo, String job_name, Integer build_num) {
    Env git_env_vars = new Env()
    git_env_vars.setEnvVars(get_build_git_data(job_name, build_num))
    buildInfo.getEnv().append(git_env_vars)
}

def publish_to_artifactory_wo_retention(
        push_build_info = true,
        String repo_name  = "${RTF_REPOSITORY}",
        def job_name = "${JOB_NAME}",
        def build_num = "${BUILD_NUMBER}",
        def server_id = null,
        def deb_distributions = [],
        String prefix=""
) {
    try {
        if (env.DO_NOT_PUBLISH_ARTIFACTORY && env.DO_NOT_PUBLISH_ARTIFACTORY == "true") {
            return
        }
        if(server_id == null) {
            server_id = get_default_artifactory()
        }
        def server = Artifactory.server "${server_id}"
        def buildInfo = newBuildInfo()
        buildInfo.setName(job_name)
        buildInfo.setNumber(build_num)
        collectEnv(buildInfo.getEnv())

        update_git_data(buildInfo, "${JOB_NAME}", "${BUILD_NUMBER}".toString().toInteger())

        print("Skipping build retention")

        String deb_distribution = ""
        if (deb_distributions) {
            String distributions = ""
            for(distribution in deb_distributions) {
                distributions= distributions+"deb.distribution="+distribution.trim()+";"
            }
            deb_distribution = distributions
        }
        else {
            deb_distribution = "deb.distribution=focal;"
            if(env.DEB_DISTRIBUTION) {
                distributions = ""
                for(distribution in env.DEB_DISTRIBUTION.split(";")) {
                    distributions= distributions+"deb.distribution="+distribution.trim()+";"
                }
                deb_distribution = distributions
            }
        }
        println("Publishing artifacts distribution ${deb_distribution} to ${repo_name} repo for ${job_name} # ${build_num} to artifactory server ${server_id}")
        def deb_files = sh(returnStdout: true, script:'ls -1 ${PKG_DIR} | grep ".*deb$"')
        for (deb_file in deb_files.split("\n")){
            sh "dpkg-name ${PKG_DIR}/${deb_file}"
        }
        def moved_deb_files = sh(returnStdout: true, script:'ls -1 ${PKG_DIR} | grep ".*deb$"')
        for (deb_file in moved_deb_files.split("\n")){
            deb_name = "${deb_file.split('\\.')[0]}"
            def uploadSpec = """{
                "files": [
                    {
                        "pattern": "${PKG_DIR}/${deb_file}",
                        "target": "${repo_name}/pool/main/${deb_file[0]}/${deb_name}.ns/${prefix}${deb_file}",
                        "props" : "${deb_distribution}deb.component=main;deb.architecture=amd64"
                    }
                ]
            }"""
            server.upload spec: uploadSpec, failNoOp: true, buildInfo: buildInfo
        }
        if(push_build_info) {
            server.publishBuildInfo buildInfo
        }
    } catch(Exception exception){
        abort_build("publish_to_artifactory", new SharedLibException(exception))
    }
}

def publish_to_artifactory(
        push_build_info = true,
        String repo_name  = "${RTF_REPOSITORY}",
        def job_name = "${JOB_NAME}",
        def build_num = "${BUILD_NUMBER}",
        def server_id = null,
        def deb_distributions = [],
        String prefix=""
) {
    try {
        if (env.DO_NOT_PUBLISH_ARTIFACTORY && env.DO_NOT_PUBLISH_ARTIFACTORY == "true") {
            return
        }
        if(server_id == null) {
            server_id = get_default_artifactory()
        }
        def server = Artifactory.server "${server_id}"
        def buildInfo = newBuildInfo()
        buildInfo.setName(job_name)
        buildInfo.setNumber(build_num)
        collectEnv(buildInfo.getEnv())

        update_git_data(buildInfo, "${JOB_NAME}", "${BUILD_NUMBER}".toString().toInteger())

        String deb_distribution = ""
        if (deb_distributions) {
            String distributions = ""
            for(distribution in deb_distributions) {
                distributions= distributions+"deb.distribution="+distribution.trim()+";"
            }
            deb_distribution = distributions
        }
        else {
            deb_distribution = "deb.distribution=focal;"
            if(env.DEB_DISTRIBUTION) {
                distributions = ""
                for(distribution in env.DEB_DISTRIBUTION.split(";")) {
                    distributions= distributions+"deb.distribution="+distribution.trim()+";"
                }
                deb_distribution = distributions
            }
        }
        println("Publishing artifacts distribution ${deb_distribution} to ${repo_name} repo for ${job_name} # ${build_num} to artifactory server ${server_id}")
        def deb_files = sh(returnStdout: true, script:'ls -1 ${PKG_DIR} | grep ".*deb$"')
        for (deb_file in deb_files.split("\n")){
            sh "dpkg-name ${PKG_DIR}/${deb_file}"
        }
        def moved_deb_files = sh(returnStdout: true, script:'ls -1 ${PKG_DIR} | grep ".*deb$"')
        for (deb_file in moved_deb_files.split("\n")){
            deb_name = "${deb_file.split('\\.')[0]}"
            def uploadSpec = """{
                "files": [
                    {
                        "pattern": "${PKG_DIR}/${deb_file}",
                        "target": "${repo_name}/pool/main/${deb_file[0]}/${deb_name}.ns/${prefix}${deb_file}",
                        "props" : "${deb_distribution}deb.component=main;deb.architecture=amd64"
                    }
                ]
            }"""
            server.upload spec: uploadSpec, failNoOp: true, buildInfo: buildInfo
        }
        if(push_build_info) {
            server.publishBuildInfo buildInfo
        }
    } catch(Exception exception){
        abort_build("publish_to_artifactory", new SharedLibException(exception))
    }
}

def publish_deb_with_cli(
        push_build_info = true,
        String repo_name  = "${RTF_REPOSITORY}",
        def job_name = "${JOB_NAME}",
        def build_num = "${BUILD_NUMBER}",
        def server_id = null,
        def deb_distributions = [],
        String prefix="",
        boolean retention = true) {

    if (env.DO_NOT_PUBLISH_ARTIFACTORY && env.DO_NOT_PUBLISH_ARTIFACTORY.toBoolean()) {
        return
    }

    def retry = env.RETRY == null ? 2 : env.RETRY.toInteger()
    def retry_wait = env.RETRY_WAIT == null ? 5 : env.RETRY.toInteger()
    def threads_count = env.THREADS_COUNT == null ? 1 : env.THREADS_COUNT.toInteger()

    env.JFROG_CLI_ENV_EXCLUDE='*password*;*psw*;*secret*;*key*;*token*;*netrc*'

    println("Collecting Build Environment Variables...")
    def git_build_data_map = get_build_git_data("${JOB_NAME}", build_num.toInteger())
    def env_cmd = ""

    for (item in git_build_data_map.keySet()) {
        item_repl = item.toString().replaceAll("-","_")
        env_cmd = env_cmd +  " export ${item_repl}=${git_build_data_map[item]} "
    }
    sh " ${env_cmd} && jf rt bce ${job_name} ${build_num} "

    if(server_id == null) {
        server_id = get_default_artifactory()
    }

    String deb_distribution = ""
    if (deb_distributions) {
        String distributions = ""
        for(distribution in deb_distributions) {
            distributions = distributions+"deb.distribution="+distribution.trim()+";"
        }
        deb_distribution = distributions
    }
    else {
        deb_distribution = "deb.distribution=focal;"
        if(env.DEB_DISTRIBUTION) {
            distributions = ""
            for(distribution in env.DEB_DISTRIBUTION.split(";")) {
                distributions= distributions+"deb.distribution="+distribution.trim()+";"
            }
            deb_distribution = distributions
        }
    }

    switch(deb_distribution) {
        case "deb.distribution=focal;":
            deb_string = "focal/main/amd64";
            break;
        case "deb.distribution=xenial;":
            deb_string = "xenial/main/amd64";
            break;
        default:
            deb_string = "xenial,focal/main/amd64";
            break;
    }

    println("Publishing artifacts distribution ${deb_distribution} to ${repo_name} repo \
        for ${job_name} # ${build_num} to artifactory server ${server_id}")

    sh "ls -1 ${PKG_DIR}/*.deb | xargs -I {} /usr/bin/dpkg-name {}"

    dir("${PKG_DIR}") {
        sh "jf rt u --build-name ${job_name} --build-number ${build_num} --regexp=true --retries=${retry} --retry-wait-time=${retry_wait}s \
        --threads=${threads_count} --deb \"$deb_string\" \"(((.)[^.]*).*deb\$)\" ${repo_name}/pool/main/{3}/{2}.ns/${prefix}{1} --recursive=false"
    }

    if(push_build_info) {
        println("Publishing Build...")
        def cmd = "jf rt build-publish ${job_name} ${build_num} "
        sh "${cmd}"
    }
}

def get_rtf_url(server_id) {
    def rtfMap = [:]
    def rtf_url = ""
    rtfMap["artifactory"] = "https://artifactory.netskope.io/artifactory/";
    rtfMap["artifactory-hen"] = "https://artifactory-ep-hen.netskope.io/artifactory/";
    rtfMap["artifactory-kiwi"] = "https://artifactory-ep-kiwi-internal.netskope.io/artifactory/";
    rtfMap["artifactory-rd"] = "https://artifactory-rd.netskope.io/artifactory/";

    rtf_url = rtfMap.containsKey(server_id) ? rtfMap[server_id] : rtfMap["artifactory"]
    return rtf_url
}


def scan_build_using_xray (
        def job_name = "${JOB_NAME}",
        def build_num = "${BUILD_NUMBER}",
        boolean fail_build = false,
        def server_id = null ) {
    if (env.DO_NOT_PUBLISH_ARTIFACTORY && env.DO_NOT_PUBLISH_ARTIFACTORY == "true") {
        return
    }
    if (env.DO_NOT_SCAN_ARTIFACTS && env.DO_NOT_SCAN_ARTIFACTS == "true") {
        return
    }
    if(server_id == null) {
        server_id = get_default_artifactory()
    }
    def server = Artifactory.server server_id
    def scanConfig = [
        'buildName'      : job_name.toString(),
        'buildNumber'    : build_num.toString(),
        'failBuild'      : fail_build
    ]
    def scanResult = server.xrayScan scanConfig
    println("Scan Result can be found at :: "+ scanResult.getScanUrl())
    println("Scan summary :: "+ scanResult.getScanMessage())
    println("Scan Result :: \n"+ scanResult)
}

def publish_pypi_to_artifactory(
        push_build_info = true,
        String repo_name  = "${RTF_REPOSITORY}",
        def job_name = "${JOB_NAME}",
        def build_num = "${BUILD_NUMBER}",
        def server_id = null
) {
    try {
        if (env.DO_NOT_PUBLISH_ARTIFACTORY && env.DO_NOT_PUBLISH_ARTIFACTORY == "true") {
            return
        }
        if(server_id == null) {
            server_id = get_default_artifactory()
        }
        println("Publishing artifacts to ${repo_name} repo for ${job_name} # ${build_num} to artifactory server ${server_id}")

        def moved_tar_files = sh(returnStdout: true, script:'ls -1 ${COMMON_PKG_DIR} | grep ".*tar.gz$"')
        rtBuildInfo (
                captureEnv: true,
                buildName : "${job_name}",
                buildNumber:  "${build_num}"
                )
        for (tar_file in moved_tar_files.split("\n")){
            tar_name = "${tar_file.split('\\-')[0]}"
            rtUpload (
                    serverId: "${server_id}",
                    buildName: "${job_name}",
                    buildNumber: "${build_num}",
                    spec: """{
                        "files": [
                            {
                                "pattern": "${COMMON_PKG_DIR}/${tar_file}",
                                "target": "${repo_name}/${tar_file[0]}/${tar_name}/${tar_file}"
                            }
                        ]
                    }"""
                    )
        }
        if(push_build_info) {
            publish_build_info(repo_name, job_name, build_num, server_id )
        }
    } catch(Exception e){
        println("Exception Occurred")
        printStackTrace(e)
    }
}

def publish_zip_files_to_artifactory(
        String parent,
        String repo_name  = "${RTF_REPOSITORY}",
        def job_name = "${JOB_NAME}",
        def build_num = "${BUILD_NUMBER}",
        def server_id = null,
        def push_build_info = true
) {
    if (env.DO_NOT_PUBLISH_ARTIFACTORY && env.DO_NOT_PUBLISH_ARTIFACTORY == "true") {
        return
    }
    if(server_id == null) {
        server_id = get_default_artifactory()
    }
    println("Publishing artifacts to ${repo_name} repo for ${job_name} # ${build_num} to artifactory server ${server_id}")
    def zip_files_map = [:]
    def zip_files = sh(returnStdout: true, script:'ls -1 ${PKG_DIR} | grep ".*zip"')
    for (zip_file in zip_files.split("\n")){
        zip_files_map[zip_file] = zip_file.replace("-${VERSION}.zip", "")
    }
    print(zip_files_map)
    rtBuildInfo (
            captureEnv: true,
            buildName : "${job_name}",
            buildNumber:  "${build_num}"
            )
    for(zip_file in zip_files_map.keySet()) {
        zip_file_name = zip_files_map[zip_file]
        sh "jf rt u --build-name ${job_name} --build-number ${build_num} --regexp=true --retries=3 --retry-wait-time=1s \
        ${PKG_DIR}/${zip_file} ${repo_name}/${parent}/${zip_file[0]}/${zip_file_name}/${zip_file}"
    }

    if(push_build_info) {
        println("Publishing Build...")
        def cmd = "jf rt bce ${job_name} ${build_num}; jf rt build-publish ${job_name} ${build_num} "
        sh "${cmd}"
    }
}

def publish_build_info(
        String repo_name  = "${RTF_REPOSITORY}",
        def job_name = "${JOB_NAME}",
        def build_num = "${BUILD_NUMBER}",
        def server_id = null) {
    if(server_id == null) {
        server_id = get_default_artifactory()
    }
    try {
        rtPublishBuildInfo (
                serverId: "${server_id}",
                buildName: "${job_name}",
                buildNumber: "${build_num}"
                )
    } catch(Exception exception){
        abort_build("publish_build_info", new SharedLibException(exception))
    }
}

def reindex_artifact_repo(def reindex_url, def repo_name) {
    withCredentials([
        string(credentialsId: 'ARTIFACTORY_API_KEY', variable: 'API_KEY')
    ]) {
        response = httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON',
        httpMode  : "POST",
        customHeaders: [
            [maskValue: true, name: 'X-JFrog-Art-Api', value: API_KEY]
        ], ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
        url: "${reindex_url}${repo_name}",
        validResponseCodes: '200'
    }
}

def publish_to_apache() {
    if (env.DO_NOT_PUBLISH_WWW && env.DO_NOT_PUBLISH_WWW == "true") {
        return
    }
    if(env.BUILD_TAG){
        env.BRANCH = env.BUILD_TAG
    }
    else{
        env.BRANCH = env.RELEASE
    }
    try {
        sh '''
      if [ ! -d ${SHA} ]; then mkdir -p ${SHA}; fi
      ls -l *.deb | awk -F ' ' '{print $9}'| awk -F '.' '{print $1}' | xargs -I {} cp "{}.deb" "${WWW}/{}-${BRANCH}-${BUILD_NUMBER}.deb" || { echo "Could not copy {} deb package to WWW"; exit 1; }
      ls -l *.deb | awk -F ' ' '{print $9}'| awk -F '.' '{print $1}' | xargs -I {} ln -sf ${WWW}/{}-${BRANCH}-${BUILD_NUMBER}.deb ${WWW}/{}-${BRANCH}-latest.deb
      ls -l *.deb | awk -F ' ' '{print $9}'| awk -F '.' '{print $1}' | xargs -I {} sha1sum ${WWW}/{}-${BRANCH}-${BUILD_NUMBER}.deb | awk '{print $1}' > ${SHA}/{}-${BRANCH}-${BUILD_NUMBER}.deb.sha1
      '''
    } catch(Exception e) {
        abort_build("publish_to_apache", new SharedLibException(e))
    }
}

def archive_publish_to_apt(boolean delete = false) {
    archiveArtifacts artifacts: 'build/pkg/*.deb', fingerprint: true, onlyIfSuccessful: true
    down_stream_job = "Publish-to-APT"
    build(
            job: down_stream_job,
            parameters: [
                string(name: 'UP_JOB_NAME', value: "${JOB_NAME}"),
                string(name: 'BUILD_NO', value: "${BUILD_NUMBER}"),
                booleanParam(name: 'DELETE_PKGS', value: delete)
            ],
            wait: false
            )
}

def publish_to_apt_repo(boolean delete = false, boolean doAbort = true) {
    if (env.DO_NOT_PUBLISH_APT && env.DO_NOT_PUBLISH_APT == "true") {
        return
    }
    try {
        keep_count = "${env.KEEP_COUNT}"
        if("${env.APT}".toLowerCase() == "release") {
            keep_count = 10
        }
        if (delete) {
            cmd = "/opt/ns/aptly/bin/aptly.py --pkgs=${env.PKG_DIR} --channel=${env.APT} --keep=${keep_count} --delete"
        } else {
            cmd = "/opt/ns/aptly/bin/aptly.py --pkgs=${env.PKG_DIR} --channel=${env.APT} --keep=${keep_count}"
        }
        sh cmd
    } catch(Exception e) {
        def exception  = new SharedLibException(e)
        if(doAbort) {
            abort_build("publish_to_apt_repo", exception)
        }
        else {
            throw exception
        }
    }
}

def build_client_packages() {
    try {
        sh '''
      cd ${NS_BUILD_DIR}
      rm -rf build/pkg/adconnector
      make -f compile/adconnector.mk VER=${VERSION} INSTALLER_PATH=${ADCONNECTOR_PATH}
      make -f compile/clientinstallers.mk VER=${VERSION} INSTALLER_PATH=${CLIENT_PATH}
      '''
    } catch(Exception e) {
        abort_build("build_client_packages", new SharedLibException(e))
    }
}

def build_adconnector_package(def server_id , def repo_name, def branch_type, def release_version) {
    def version_found = get_client_adaptors_build_version(server_id, "${repo_name}", "${branch_type}", "${release_version}")
    def artifacts = get_client_adaptors_builds(server_id, "${repo_name}", "${branch_type}", "${version_found}")
    dir("${NS_BUILD_DIR}/${repo_name}") {
        sh artifacts.join("\n")
    }
    sh "make -f compile/adconnector.mk VER=${VERSION} INSTALLER_PATH=${NS_BUILD_DIR}/${repo_name}"
}

def build_clientinstaller_package(def server_id, def repo_name, def branch_type, def release_version) {
    def version_found = get_client_adaptors_build_version(server_id, "${repo_name}", "${branch_type}", "${release_version}")
    def artifacts = get_client_adaptors_builds(server_id, "${repo_name}", "${branch_type}", "${version_found}")
    dir("${NS_BUILD_DIR}/${repo_name}") {
        sh artifacts.join("\n")
    }
    sh "make -f compile/clientinstallers.mk VER=${VERSION} INSTALLER_PATH=${NS_BUILD_DIR}/${repo_name}"
}

def get_client_adaptors_builds(def server_id , def repo_name, def branch_type, def version) {
    def server = Artifactory.server "${server_id}"
    def files = []
    def search_path = "${repo_name}/${branch_type}/${version}/latest/Release/*"
    def cmd = "jf rt search ${search_path} --server-id ${server_id} --include-dirs --transitive --retries 3 --retry-wait-time 5s"
    def response = sh(returnStdout: true, script: "${cmd}").trim()
    print("Search result : " + response)
    def children = new groovy.json.JsonSlurper().parseText(response)
    for (child in children) {
        if(child["type"] == "file"){
            path = child["path"]
            folder = path.split('/')[0]
            def isCacheRepo = folder =~ /.*-cache$/
            if(!isCacheRepo) {
                folder = path.split('/')[0]
                def file_name = path.split('/')[-1]
                files.add("curl -O ${server.getUrl()}/${repo_name}/${branch_type}/${version}/latest/Release/${file_name}")
            }
        }
    }
    return files
}

def get_client_adaptors_build_version(def server_id = null, def repo_name, def branch_type, def release_version) {
    if(server_id == null) {
        server_id = get_default_artifactory()
    }
    def server = Artifactory.server "${server_id}"
    def versions = []
    def version_found= null
    def search_path = "${repo_name}/${branch_type}/*/latest/Release"
    def cmd = "jf rt search ${search_path} --server-id ${server_id} --include-dirs --transitive --retries 3 --retry-wait-time 5s"
    def response = sh(returnStdout: true, script: "${cmd}").trim()
    print("Search result : " + response)
    def children = new groovy.json.JsonSlurper().parseText(response)
    for (child in children) {
        if(child["type"] == "folder"){
            path = child["path"]
            version = path.split('/')[-3]
            if(release_version.split('\\.')[0] == version.split('\\.')[0]) {
                versions.add(version)
            }
        }
    }
    if(versions.size() == 0){
        error("Couldn't find  ${repo_name} of version ${release_version}")
    }
    for (version in versions) {
        if(release_version == version) {
            version_found = version
            break
        }
    }
    if(version_found == null) {
        version_found = versions.sort()[-1]
        // this would fail, If we have more than 9 hotfix builds
    }
    print("Version found is ${version_found}")
    return version_found
}

def build_client_adconnector_packages(String adconnector_path, String client_path, String build_tag) {
    try {
        println("Build Tag: " + build_tag)
        adconnector_latest = get_latest_installer_path(adconnector_path, build_tag)
        println("Picking up Adaptors package from " + adconnector_latest)
        client_latest = get_latest_installer_path(client_path, build_tag)
        println("Picking up Client Installers from " + client_latest)
        sh '''
      cd ${NS_BUILD_DIR}
      rm -rf build/pkg/adconnector
      make -f compile/adconnector.mk VER=${VERSION} INSTALLER_PATH=''' + adconnector_latest + '''
      make -f compile/clientinstallers.mk VER=${VERSION} INSTALLER_PATH=''' + client_latest + '''
    '''
    } catch(Exception e) {
        abort_build("build_client_packages", new SharedLibException(e))
    }
}

def apt_sync(String directory, String url = 'http://apt-sync-01.mgmt.netskope.com:5000/aptsync/start') {
    try {
        def data = [
            job: JOB_NAME,
            node: NODE_NAME,
            build_url: BUILD_URL,
            workspace: WORKSPACE,
            version: VERSION,
            channel: APT,
            directory: directory,
            keep_count: KEEP_COUNT
        ]
        def json = JsonOutput.toJson(data)
        def response = httpRequest url: url, acceptType: 'APPLICATION_JSON', contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: json
        println('Status: '+response.status)
        println('Response: '+response.content)
    } catch(Exception e) {
        abort_build("apt_sync", new SharedLibException(e))
    }
}

def publish_pkgs(boolean isPostAction = false) {
    if (env.DO_NOT_PUBLISH_PKGS && env.DO_NOT_PUBLISH_PKGS == "true") {
        return
    }
    String release_ver = get_release_ver()
    try {
        for (repo in repo_list) {
            if ( repo != 'jenkins-pipeline') {
                www_path = "/builds/${APT}/${repo}/${release_ver}/${BUILD_NUMBER}"
            }
        }
        sh """
      mkdir -p ${www_path}
      cd ${www_path}
      cp ${PKG_DIR}/*.deb ${www_path}/
      ls -1 *.deb | xargs -I {} /usr/bin/dpkg-name {}
    """
        apt_sync(www_path, "https://jxiitsdf29.execute-api.us-west-2.amazonaws.com/production/apt_sync")
    }
    catch(Exception e){
        if(isPostAction){
            abort_build("publish_pkgs", new PostBuildException(e))
        }
        else{
            abort_build("publish_pkgs", new SharedLibException(e))
        }
    }
}

def get_email_list(String rcpt) {
    String[] email_list = rcpt.tokenize(",[ ]")
    def build = currentBuild
    while(build != null && build.result != 'SUCCESS') {
        for (changeLog in build.changeSets) {
            for(entry in changeLog.items) {
                msg = entry.comment
                String[] matches = msg.findAll("[a-zA-Z.]+@netskope.com")
                email_list += matches
            }
        }
        build = build.previousBuild
    }
    def emails = (Set) email_list
    release_recipients(emails)
    def filtered_emails = []
    def prefix_exclusions = ["eng", "noreply", "support"]
    for (email in emails) {
        boolean exclude = false
        for(exclusion in prefix_exclusions) {
            if(email.toLowerCase().startsWith(exclusion)) {
                exclude = true
                break
            }
        }
        if(!exclude) {
            filtered_emails.add(email)
        }
    }
    return filtered_emails.join(",")
}

def get_current_build_code_contributors(String rcpt) {
    String[] email_list = rcpt.tokenize(",[ ]")
    def build = currentBuild
    for (changeLog in build.changeSets) {
        for(entry in changeLog.items) {
            msg = entry.comment
            String[] matches = msg.findAll("[a-zA-Z.]+@netskope.com")
            email_list += matches
        }
    }
    def emails = (Set) email_list
    return emails.join(",")
}

@NonCPS
def release_recipients(def emails){
    String release_locator = new String("release-pipeline")
    def build = currentBuild
    if(build.getFullProjectName().endsWith(release_locator)){
        emails.add("ndandia@netskope.com")
    }
}
@NonCPS
def process_changeset() {
    String[] jiras = []
    def build = currentBuild
    for (changeLog in build.changeSets) {
        for(entry in changeLog.items) {
            String ts = (String) new Date(entry.timestamp)
            echo "${entry.commitId} by ${entry.author.fullName} <" + entry.getAuthorEmail() + "> on " + ts
            msg = entry.msg
            String[] matches = msg.findAll("(ENG|OPS|TLS)-[0-9]+").toSet()
            jiras += matches
        }
    }
    def jira_list = (Set) jiras
    if ( jira_list.size() == 0 ) {
        println("JIRA List is empty! Nothing to update.")
    } else {
        update_jira(jira_list)
    }
}

@NonCPS
def get_jira_tickets() {
    String[] jiras = []
    def build = currentBuild
    for (changeLog in build.changeSets) {
        for(entry in changeLog.items) {
            msg = entry.msg
            String[] matches = msg.findAll("(ENG|OPS|TLS)-[0-9]+").toSet()
            jiras += matches
        }
    }
    def jira_list = (Set) jiras
    if ( jira_list.size() != 0 ) {
        return jira_list
    }
}

def jira_add_build_info(jira_key) {
    String build_job_name_field = "customfield_16176"
    String build_version_field = "customfield_16177"
    println("Adding Build Information to JIRA: ${jira_key}")
    url = "https://netskope.atlassian.net/rest/api/2/issue/${jira_key}"
    def build_job_name_object = [update:["${build_job_name_field}":[[add:JOB_NAME]]]]
    def build_version_object = [update:["${build_version_field}":[[add:VERSION]]]]
    def build_job_name = JsonOutput.toJson(build_job_name_object)
    def build_version = JsonOutput.toJson(build_version_object)
    def response
    try {
        response = httpRequest acceptType: 'APPLICATION_JSON', authentication: 'netskope-tools', consoleLogResponseBody: true, contentType: 'APPLICATION_JSON', httpMode: 'PUT', ignoreSslErrors: true, requestBody: build_job_name, url: url, validResponseCodes: '204'
    } catch(Exception ex) {
        println(ex.toString());
        println(ex.getMessage());
    }
    println('HTTP Status Code: '+response.status)
    try {
        response = httpRequest acceptType: 'APPLICATION_JSON', authentication: 'netskope-tools', consoleLogResponseBody: true, contentType: 'APPLICATION_JSON', httpMode: 'PUT', ignoreSslErrors: true, requestBody: build_version, url: url, validResponseCodes: '204'
    } catch(Exception ex) {
        println(ex.toString());
        println(ex.getMessage());
    }
    println('HTTP Status Code: '+response.status)
}

def jira_add_commit_info(String jira_id, String id, String name, String email, String ts) {
    txt = "*${id}* by [${name}|mailto:${email}] on ${ts}"
    jiraComment body: txt, issueKey: jira_id
}

def update_jira(jira_list, boolean isPostAction = false) {
    url = "https://us-west1-ns-cicd.cloudfunctions.net/ep-jira-build-info-updater"
    def commit_branch = ""
    if ( env.RELEASE_BRANCH && "${RELEASE_BRANCH}" != "" ) {
        commit_branch = "${RELEASE_BRANCH}"
    } else if ( env.BRANCH && "${BRANCH}" != "" ) {
        commit_branch = "${BRANCH}"
    }
    try {
        def BUILD_RELEASE_VER = VERSION.replace(".", ",").split(',') as List
        BUILD_RELEASE_VER = BUILD_RELEASE_VER[0..-2].join(".")
        println("BUILD_RELEASE_VER::"+BUILD_RELEASE_VER)
        def buildr_vars = [BUILD_RELEASE_VER, VERSION]
        for( build_var in buildr_vars){
            def data = [
                job_name: JOB_NAME,
                build_version: build_var,
                commit_branch: commit_branch,
                jira_list: jira_list,
            ]
            println(data)
            def json = JsonOutput.toJson(data)
            println(json)
            def response = httpRequest url: url, acceptType: 'APPLICATION_JSON', contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: json
            println('Status: '+response.status)
            println('Response: '+response.content)
        }
    } catch(Exception ex) {
        println(ex.toString());
        println(ex.getMessage());
        if(isPostAction){
            abort_build("update_jira", new PostBuildException(e))
        }
        else{
            abort_build("update_jira", new SharedLibException(e))
        }
    }
}

@NonCPS
def changes_since_last_success() {
    String[] jiras = []
    def build = currentBuild
    while(build != null && build.result != 'SUCCESS') {
        for (changeLog in build.changeSets) {
            for(entry in changeLog.items) {
                msg = entry.msg
                String[] matches = msg.findAll("(ENG|OPS|TLS|EP)-[0-9]+").toSet()
                jiras += matches
            }
        }
        build = build.previousBuild
    }
    def jira_list = (Set) jiras
    println("Affected JIRAS since last successful builds are: " + jira_list)
    update_jira(jira_list)
}

def getCommitID(String job_name, String build_num) {
    Integer build_number = build_num.toInteger()
    def job = Jenkins.instance.getItemByFullName(job_name)
    def job_instance = job.getBuildByNumber(build_number)
    def actions = job_instance.getActions(hudson.plugins.git.util.BuildData)
    String git_commit_sha1 = ""
    actions.each {
        if (! it.getRemoteUrls().contains("git@github.com:netSkope/jenkins-pipeline.git") && ! it.getRemoteUrls().contains("git@github.com:netSkope/infrastructure.git") && ! it.getRemoteUrls().contains("git@github.com:netSkope/npa_core.git")) {
            git_commit_sha1 = it.getLastBuiltRevision().getSha1().name()
        }
    }
    return git_commit_sha1
}

def get_commit_id_by_remote(String job_name, String build_num, String remote_url) {
    Integer build_number = build_num.toInteger()
    def job = Jenkins.instance.getItemByFullName(job_name)
    def job_instance = job.getBuildByNumber(build_number)
    def actions = job_instance.getActions(hudson.plugins.git.util.BuildData)
    String git_commit_sha1 = ""
    actions.each {
        if (it.getRemoteUrls().contains(remote_url)) {
            git_commit_sha1 = it.getLastBuiltRevision().getSha1().name()
        }
    }
    return git_commit_sha1
}

def get_commit_id_by_repo_name(String job_name, String build_num, String repo_name) {
    Integer build_number = build_num.toInteger()
    def job = Jenkins.instance.getItemByFullName(job_name)
    def job_instance = job.getBuildByNumber(build_number)
    def actions = job_instance.getActions(hudson.plugins.git.util.BuildData)
    for(action in actions) {
        for (git_remote_url in action.getRemoteUrls()) {
            if (git_remote_url.endsWith(repo_name+".git")) {
                return action.getLastBuiltRevision().getSha1().name()
            }
        }
    }
    error("Couldn't find revision for ${job_name} # ${build_num} repo_name : ${repo_name}")
}

def is_same_github_tag_present(String base_url, String tag, String commit_id) {
    def response = get_github_tags(base_url, tag)
    if ( response.status == 200 ) {
        def release_tags = readJSON text: response.content
        if (release_tags instanceof  net.sf.json.JSONObject) {
            if(release_tags["ref"] == "refs/tags/"+tag && release_tags["object"]["sha"] == commit_id){
                return true
            }
        }
        else if (release_tags instanceof  net.sf.json.JSONArray) {
            for(tag_info in release_tags) {
                if(tag_info["ref"] == "refs/tags/"+tag && tag_info["object"]["sha"] == commit_id ){
                    return true
                }
            }
        }
    } else {
        return false;
    }
}

def get_github_tags(String base_url, String tag) {
    String url = base_url + 'git/refs/tags/' + tag
    try {
        withCredentials([
            string(credentialsId: 'github-token', variable: 'TOKEN')
        ]) {
            return httpRequest (
                    consoleLogResponseBody: true, contentType: 'APPLICATION_JSON',
                    customHeaders: [
                        [maskValue: false, name: 'Accept', value: 'application/vnd.github.v3.full+json'],
                        [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
                        [maskValue: true, name: 'Authorization', value: 'Token ' + TOKEN]
                    ], ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
                    url: url,
                    validResponseCodes: '200:404'
                    )
        }
    }
    catch(Exception e) {
        println("Failed to get tags in ${base_url}")
        throw e
    }
}

def is_github_tag_present(String base_url, String tag) {
    def response = get_github_tags(base_url, tag)
    if ( response.status == 200 ) {
        def release_tags = readJSON text: response.content
        if (release_tags instanceof  net.sf.json.JSONObject) {
            if(release_tags["ref"] == "refs/tags/"+tag){
                return true
            }
        }
        if (release_tags instanceof  net.sf.json.JSONArray) {
            for(tag_info in release_tags) {
                if(tag_info["ref"] == "refs/tags/"+tag){
                    return true
                }
            }
        }
    } else {
        return false;
    }
}

def get_github_tag_head(String repo_name, String tag) {
    String base_url = get_repo_map()[repo_name]
    String url = base_url + 'git/refs/tags/' + tag
    def response
    withCredentials([
        string(credentialsId: 'github-token', variable: 'TOKEN')
    ]) {
        response = httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON',
        customHeaders: [
            [maskValue: false, name: 'Accept', value: 'application/vnd.github.v3.full+json'],
            [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
            [maskValue: true, name: 'Authorization', value: 'Token ' + TOKEN]
        ], ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
        url: url,
        validResponseCodes: '200:404'
    }
    if ( response.status == 200 ) {
        def release_tags = readJSON text: response.content
        if (release_tags instanceof  net.sf.json.JSONObject) {
            if(release_tags["ref"] == "refs/tags/"+tag){
                return release_tags["object"]["sha"]
            }
        }
        if (release_tags instanceof  net.sf.json.JSONArray) {
            for(tag_info in release_tags) {
                if(tag_info["ref"] == "refs/tags/"+tag){
                    return tag_info["object"]["sha"]
                }
            }
        }
    } else {
        error("Failed to find the tag {tag} in {base_url}");
    }
}

def gitlab_get_tag(String base_url, String tag) {
    String url = base_url + 'repository/tags/' + tag
    def response
    withCredentials([
        string(credentialsId: 'gitlab-token', variable: 'TOKEN')
    ]) {
        response = httpRequest consoleLogResponseBody: true, acceptType: 'APPLICATION_JSON',
        customHeaders: [
            [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
            [maskValue: true, name: 'Private-Token', value: TOKEN]
        ], ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
        url: url,
        validResponseCodes: '200:404'
    }
    if ( response.status == 200 ) {
        return true;
    } else {
        return false;
    }
}

def github_delete_tag(String base_url, String tag) {
    github_delete_release(base_url, tag)
    String url = base_url + 'git/refs/tags/' + tag
    def response
    withCredentials([
        string(credentialsId: 'github-token', variable: 'TOKEN')
    ]) {
        response = httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON',
        customHeaders: [
            [maskValue: false, name: 'Accept', value: 'application/vnd.github.v3.full+json'],
            [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
            [maskValue: true, name: 'Authorization', value: 'Token ' + TOKEN]
        ], httpMode: 'DELETE',
        ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
        url: url,
        validResponseCodes: '204'
    }
    if ( response.status == 204 ) {
        return true;
    } else {
        return false;
    }
}

def github_delete_release(String base_url, String release) {
    println("Delete releases in ${base_url} for release [${release}]")
    String releases_url = base_url + 'releases'
    // Iterating the release check for three times. As the GitHub API isn't returning the existing releases at times.
    for (counter = 0; counter<3; counter++){
        def response
        withCredentials([
            string(credentialsId: 'github-token', variable: 'TOKEN')
        ]) {
            def releases = httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON',
            customHeaders: [
                [maskValue: false, name: 'Accept', value: 'application/vnd.github.v3.full+json'],
                [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
                [maskValue: true, name: 'Authorization', value: 'Token ' + TOKEN]
            ], ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
            url: releases_url,
            validResponseCodes: '200'
            def all_releases = readJSON text: releases.content
            for(release_node in all_releases) {
                if (release_node["name"] == release) {
                    response = httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON',
                    customHeaders: [
                        [maskValue: false, name: 'Accept', value: 'application/vnd.github.v3.full+json'],
                        [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
                        [maskValue: true, name: 'Authorization', value: 'Token ' + TOKEN]
                    ], httpMode: 'DELETE',
                    ignoreSslErrors: true, responseHandle: 'NONE',
                    url: release_node["url"],
                    validResponseCodes: '204'
                    println("Deleted release [${release}] in ${base_url}")
                    break
                }
            }
        }
    }
}

def gitlab_delete_tag(String base_url, String tag) {
    String url = base_url + 'repository/tags/' + tag
    def response
    withCredentials([
        string(credentialsId: 'gitlab-token', variable: 'TOKEN')
    ]) {
        response = httpRequest consoleLogResponseBody: true, acceptType: 'APPLICATION_JSON',
        customHeaders: [
            [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
            [maskValue: true, name: 'Private-Token', value: TOKEN]
        ], httpMode: 'DELETE',
        ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
        url: url,
        validResponseCodes: '204'
    }
    if ( response.status == 204 ) {
        return true;
    } else {
        return false;
    }
}

def create_github_release_with_notes(String tag_name, String repo_url, String commit_sha) {
    String url = repo_url + 'releases'
    def data = [
        tag_name: tag_name,
        target_commitish: commit_sha,
        name: tag_name,
        body: "Release"+ tag_name,
        draft:false,
        prerelease: false,
        generate_release_notes:true
    ]
    def json = JsonOutput.toJson(data)
    def response
    withCredentials([
        string(credentialsId: 'github-token', variable: 'TOKEN')
    ]) {
        response = httpRequest consoleLogResponseBody: true, acceptType: 'APPLICATION_JSON',
        customHeaders: [
            [maskValue: false, name: 'Accept', value: 'application/vnd.github.v3.full+json'],
            [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
            [maskValue: true, name: 'Authorization', value: 'Token ' + TOKEN]
        ], httpMode: 'POST',
        ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
        requestBody: json,
        url: url,
        validResponseCodes: '201'
    }
}

def github_create_tag(String tag, String base_url, String commit) {
    String url = base_url + 'releases'
    def data = [
        tag_name: tag,
        target_commitish: commit,
        name: tag,
        body: "Release " + tag
    ]
    def json = JsonOutput.toJson(data)
    def response
    withCredentials([
        string(credentialsId: 'github-token', variable: 'TOKEN')
    ]) {
        response = httpRequest consoleLogResponseBody: true, acceptType: 'APPLICATION_JSON',
        customHeaders: [
            [maskValue: false, name: 'Accept', value: 'application/vnd.github.v3.full+json'],
            [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
            [maskValue: true, name: 'Authorization', value: 'Token ' + TOKEN]
        ], httpMode: 'POST',
        ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
        requestBody: json,
        url: url,
        validResponseCodes: '201'
    }
    if ( response.status == 201 ) {
        return true
    } else {
        return false
    }
}

def get_repo_map(){
    println("In get_repo_map function")
    if(GIT_URLS == null) {
        def giturls_as_text = readFile "config/github-urls.yml"
        println(giturls_as_text)
        GIT_URLS = readYaml text: giturls_as_text
        println("GIT URLS are")
        println(GIT_URLS)
    }
    //return GIT_URLS["repoMap"]
}

def create_tag(String tag, String repo, String commit) {
    println("Create ${tag} tag in ${repo} repository from ${commit} SHA")
    String base_url = get_repo_map()[repo]
    if ( is_github_tag_present(base_url, tag) ) {
        if (is_same_github_tag_present(base_url, tag, commit)) {
            println("Tag [${tag}] exists from the same commit sha ${commit} in ${base_url}")
            println("Hence skipping creating tags")
            return
        }
        println("Tag with different commti sha" + tag + " exists!")
        println("Hence the existing tag will be deleted and new tag will be created")

        if ( github_delete_tag(base_url, tag) ) {
            println("Deleted existing tag!")
        } else {
            println("Failed to delete existing tag!")
            currentBuild.result = 'FAILURE'
        }
    }
    if ( ! github_create_tag(tag, base_url, commit) ) {
        currentBuild.result = 'FAILURE'
    }
}

def get_last_commit_sha(String repo_name, String branch) {
    println("get_last_commit_sha(${repo_name}, ${branch})")
    def base_url = get_repo_map()[repo_name]
    String url = base_url + "branches/${branch}"
    def response
    withCredentials([
        string(credentialsId: 'github-token', variable: 'TOKEN')
    ]) {
        response = httpRequest consoleLogResponseBody: true, acceptType: 'APPLICATION_JSON',
        customHeaders: [
            [maskValue: false, name: 'Accept', value: 'application/vnd.github.v3.full+json'],
            [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
            [maskValue: true, name: 'Authorization', value: 'Token ' + TOKEN]
        ], httpMode: 'GET',
        ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
        url: url,
        validResponseCodes: '200'
    }
    def txt = response.content
    def jsonSlurper = new JsonSlurper()
    def commit_sha = jsonSlurper.parseText(txt)['commit']["sha"]
    println("get_last_commit_sha(${repo_name}, ${branch}) -> ${commit_sha}")
    return commit_sha
}


def get_repo_urls_map() {
    def repoMap = [:]

    // Git hub repos
    repoMap["adaptors"] = "git@github.com:netSkope/adaptors.git";
    repoMap["app-info"] = "git@github.com:netSkope/app-info.git";
    repoMap["client"] = "git@github.com:netSkope/client.git";
    repoMap["content"] = "git@github.com:netSkope/content.git";
    repoMap["malware-info"] = "git@github.com:netSkope/malware-info.git";
    repoMap["service"] = "git@github.com:netSkope/service.git";
    repoMap["webcat-remap"] = "git@github.com:netSkope/webcat-remap.git";

    // Use the below repo for testing purpose.
    //repoMap["sandbox"] = "git@github.com:netSkope/sandbox.git";

    // Git lab repos
    repoMap["anti-mal-content"] = "git@gitlab.netskope.com:netskope/anti-mal-content.git";
    repoMap["appicons"] = "git@gitlab.netskope.com:netskope/appicons.git";
    repoMap["secure-assessment"] = "git@gitlab.netskope.com:netskope/secure-assessment.git";
    repoMap["pyproject"] = "git@gitlab.netskope.com:stevem/pyproject.git";
    repoMap["dump-cfg-vars"] = "git@gitlab.netskope.com:stevem/dump-cfg-vars.git"

    repoMap["nsmetgen"] = "git@gitlab.netskope.com:stevem/nsmetgen.git"
    repoMap["nszmesh"] = "git@gitlab.netskope.com:stevem/nszmesh.git"
    repoMap["nsmultirepo"] = "git@gitlab.netskope.com:stevem/nsmultirepo.git"
    repoMap["nsmetric"] = "git@gitlab.netskope.com:stevem/nsmetric.git"
    repoMap["autok"] = "git@gitlab.netskope.com:stevem/autok.git"

    return repoMap;
}


def promote_jenkins_build(String build_url) {
    /* promote_jenkins_build will make sure the build will be kept in the jenkins
     * forever. It will add a build description as Promoted Build # Release version
     */
    if(!build_url) {
        error("build_url cannot be null ->  refer promote_job function")
    }
    def cred_id = null
    if(build_url.startsWith("https://cisystem.netskope.io")) {
        cred_id = "cisystem-devexp"
    }
    else if(build_url.startsWith("https://iad0-cisystem.netskope.io")) {
        cred_id = "iad0-cisystem-devexp"
    }
    else if(build_url.startsWith("https://ci-feature.netskope.io")) {
        cred_id = "ci-feature-devexp"
    }
    else if(build_url.startsWith("https://gke-cisystem.netskope.io/")) {
        cred_id = "gke-cisystem-devexp"
    }
    set_promotion_description(build_url, cred_id)
    keep_build_forerver(build_url, cred_id)
}

@NonCPS
def promote_job(String job_name, String build_number) {
    Integer build_num = build_number.toInteger()
    def job_obj = Jenkins.instance.getItemByFullName(job_name)
    def job_instance = job_obj.getBuildByNumber(build_num)
    def job_env = job_instance.getEnvVars()
    String url = job_env['BUILD_URL'] + 'promote/?level=3'
    wget(url, 'jenkins-netskope')
}

def set_promotion_description(String build_url, String cred_id) {
    if(cred_id) {
        String data = URLEncoder.encode("Promoted Build # ${TAG}")
        def payload = "Submit=Save"+"&"+"description="+data
        try {
            response = httpRequest(
                    url: build_url+"submitDescription",
                    consoleLogResponseBody :false,
                    quiet :false,
                    httpMode: "POST",
                    requestBody: payload,
                    contentType  : "APPLICATION_FORM",
                    authentication: cred_id,
                    validResponseCodes: '200:302'
                    )
        }
        catch(Exception e ) {
            println("Failed to set the build description with promotion details for build ${build_url}")
            throw e
        }
    }
}


def keep_build_forerver(String build_url, String cred_id) {
    if (cred_id && !is_build_keep_forever_enabled(build_url, cred_id)) {
        try {
            httpRequest(
                    url: build_url+"toggleLogKeep",
                    consoleLogResponseBody :false,
                    quiet :false,
                    httpMode: "POST",
                    authentication: cred_id,
                    validResponseCodes: '200:302'
                    )
        }
        catch(Exception e ) {
            println("Failed to keep the build forever for build ${build_url}")
            throw e
        }
    }
}

def is_build_keep_forever_enabled(String build_url, String cred_id) {
    def response = null
    try {
        response = httpRequest(
                url: build_url+"api/json",
                consoleLogResponseBody :false,
                quiet :false,
                httpMode: "POST",
                authentication: cred_id,
                validResponseCodes: '200'
                )
    }
    catch(Exception e ) {
        println("Failed to check the build forever status for a build ${build_url}")
        throw e
    }
    def resp_body = readJSON text: response.content
    if(resp_body["keepLog"] == true) {
        return true
    }
    return false
}

def wget(String url, String cred_id) {
    withCredentials([
        usernamePassword(credentialsId: cred_id, passwordVariable: 'PASS', usernameVariable: 'USER')
    ]) {
        sh returnStatus: true, script: "wget -q --auth-no-challenge --max-redirect 0 --user ${USER} --password ${PASS} ${url}"
    }
}

def build_promotion_email(String result, String jobs, String tag, String email_rcpt) {
    if ( email_rcpt == '') {
        return
    }
    String subject = "Release " + tag + ": Build Promotion " + result
    String body = """Hi,

  Build Promotion """ + result + """ for Release """ + tag + """

  Build Promotion was Requested for:"""
    String[] job_list = params.JOBS.split(' ')
    job_list.each { job ->
        def (job_name, build_number) = job.split(':')
        body += """
    * Job Name: ${job_name}, Build Number: ${build_number}"""
    }
    body += """
  Please find the log attached!
  Thanks,
  Productivity Engineering Team

  """
    emailext body: body,
    subject: subject,
    to: email_rcpt,
    attachLog: true
}

def get_latest_installer_path(String path, String build_tag) {
    def max_tag = ""
    def cmd = 'cd ' + path + '; ls -d1 */ | tr -d "/" | grep -v \\@tmp'
    def dirList = sh (
            script: cmd,
            returnStdout: true
            ).trim().split("\n").toList()
    Integer r_major = build_tag.tokenize(".")[0].toInteger()
    Integer r_minor = build_tag.tokenize(".")[1].toInteger()
    Integer r_revision = build_tag.tokenize(".")[2].toInteger()
    Integer major = 0
    Integer minor = 0
    Integer revision = 0
    for ( folders in dirList ) {
        folder = folders.trim()
        if ( folder.matches("^[1-9][0-9]*.[0-9]+.[0-9]+") ) {
            f_major = folder.tokenize(".")[0].toInteger()
            f_minor = folder.tokenize(".")[1].toInteger()
            f_revision = folder.tokenize(".")[2].toInteger()
            if ( f_major <= r_major ) {
                if ( f_major > major ) {
                    major = f_major
                    minor = f_minor
                    revision = f_revision
                }
                if ( f_minor > minor ) {
                    minor = f_minor
                    revision = f_revision
                }
                if ( f_revision > revision ) {
                    revision = f_revision
                }
            }
        }
    }
    String version = major.toString() + "." + minor.toString() + "." + revision.toString()
    return StringUtils.stripEnd(path, '/') + '/' + version + '/latest'
}

def send_data_to_lambda(url, data) {
    def response = httpRequest url: url, acceptType: 'APPLICATION_JSON', contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: data
    println('Status: '+response.status)
    println('Response: '+response.content)
}

def publish_stage_status(status, start_time, end_time) {
    start_time = start_time as long
    end_time = end_time as long
    def data=[
        job_name:"${JOB_NAME}",
        build_no:"${BUILD_NUMBER}",
        start_time:start_time,
        end_time:end_time,
        duration:end_time - start_time,
        stage_name:env.STAGE_NAME,
        status:status
    ]
    def body_data = JsonOutput.toJson(data)
    def url = "${PUBLISH_STAGE_URL}"
    print(body_data)
    try {
        send_data_to_lambda(url, body_data)
    } catch(Exception e){
        println("Exception Occurred while sending stage status to lambda " + e)
    }
}

def get_build_tag() {
    def build_tag = ''
    if(env.RELEASE!="") {
        build_tag = env.RELEASE
        print(env.RELEASE)
    } else if(env.MAJOR_VERSION!="" && env.MINOR_VERSION!="" && env.REVISION_NUM!="") {
        print(env.MAJOR_VERSION)
        build_tag = env.MAJOR_VERSION + "." + env.MINOR_VERSION + "." + env.REVISION_NUM
    } else if(env.BUILD_TAG!="") {
        print(env.BUILD_TAG)
        build_tag = env.BUILD_TAG
    }
    return build_tag
}

def publish_job_info(status) {
    def build_tag = get_build_tag()
    print(build_tag)
    def commit_info = []
    for (changeLog in currentBuild.changeSets) {
        for(entry in changeLog.items) {
            def commit_obj = [
                commitId:entry.commitId,
                author:entry.getAuthorName(),
                comment:entry.getComment(),
                timestamp:entry.getTimestamp()
            ]
            commit_info.push(commit_obj)
        }
    }
    def data=[
        job_name:"${JOB_NAME}",
        build_no:"${BUILD_NUMBER}",
        job_url:"${BUILD_URL}",
        build_tag:build_tag,
        job_status:status,
        start_time:currentBuild.startTimeInMillis,
        duration:currentBuild.getDuration(),
        end_time:currentBuild.startTimeInMillis+currentBuild.duration,
        commit_info:commit_info
    ]
    def body_data = JsonOutput.toJson(data)
    print(body_data)
    def url = "${PUBLISH_JOB_URL}"
    try {
        send_data_to_lambda(url, body_data)
    } catch(Exception e){
        println("Exception Occurred while sending job status to lambda " + e)
    }
}

def promote_build_artifactory(job_name, build_number, art_server_id, source_repo, target_repo) {
    rtPromote (
            buildName: job_name,
            buildNumber: build_number,
            serverId: art_server_id,
            targetRepo: target_repo,
            comment: 'Promoted',
            status: 'Released',
            sourceRepo: source_repo,
            includeDependencies: true,
            failFast: true,
            copy: true
            )
}

def get_log_verbosity_level() {
    def env_vars = env.getEnvironment()
    if(env_vars.containsKey("QUIET_LOGGING") && env_vars.get("QUIET_LOGGING").toBoolean() == true) {
        return " SETUPPY_SDIST_OPTIONS=--quiet  PIP_INSTALL_FLAGS=\" --quiet --find-links=file://${NS_BUILD_DIR}/build/python_packages\""
    }
    else {
        return ""
    }
}

def get_setuppy_sdist_options() {
    if(env.QUIET_LOGGING && env.QUIET_LOGGING.toBoolean() == true) {
        return "\" --quiet \""
    }
    return ""
}

def get_common_pip_install_flags() {
    String pip_install_flags = ""
    if(env.PYPI_INDEX_URL) {
        pip_install_flags = pip_install_flags + " --index-url ${PYPI_INDEX_URL} "
    }
    if(env.PYPI_EXTRA_INDEX_URLS) {
        def py_indexes = "${PYPI_EXTRA_INDEX_URLS}".split(",") as List
        String extra_indexes = ""
        for(py_index in py_indexes) {
            extra_indexes = extra_indexes + " --extra-index-url ${py_index} "
        }
        pip_install_flags = pip_install_flags + extra_indexes
    }
    if(env.QUIET_LOGGING && env.QUIET_LOGGING.toBoolean() == true) {
        pip_install_flags = pip_install_flags +  " --quiet "
    }
    return pip_install_flags
}

def get_deb_pip_install_flags(String build_dir) {
    String pip_install_flags = " --find-links=file://${build_dir}/build/python_packages "+ get_common_pip_install_flags()
    if(env.DEB_PRE_PIP_FLAGS) {
        pip_install_flags = env.DEB_PRE_PIP_FLAGS +" "+ pip_install_flags
    }
    if(env.DEB_POST_PIP_FLAGS) {
        pip_install_flags =  " "+pip_install_flags + " "+ env.DEB_POST_PIP_FLAGS + " "
    }
    return "\"${pip_install_flags}\""
}

def get_deb_pip_deprecated_resolver_flags(String build_dir, String package_type) {
    if(package_type == "DEBIAN") {
        String deb_pip_install_flags = " --find-links=file://${build_dir}/build/python_packages "+ get_common_pip_install_flags()
        return "\"${deb_pip_install_flags}\""
    }
    else if(package_type == "DOCKER") {
        String docker_pip_install_flags = " --find-links file:///src_tree/build/python_packages "+ get_common_pip_install_flags()
        return "\"${docker_pip_install_flags}\""
    }
    else {
        error("package_type for get_deb_pip_deprecated_resolver_flags function accepts either 'DEBIAN' or 'DOCKER'")
    }
}

def get_docker_pip_install_flags() {
    String pip_install_flags = " --find-links file:///src_tree/build/python_packages "+ get_common_pip_install_flags()
    if(env.DOCKER_PRE_PIP_FLAGS) {
        pip_install_flags = env.DOCKER_PRE_PIP_FLAGS +" "+ pip_install_flags
    }
    if(env.DOCKER_POST_PIP_FLAGS) {
        pip_install_flags =  " "+pip_install_flags + " "+ env.DOCKER_POST_PIP_FLAGS + " "
    }
    return "\"${pip_install_flags}\""
}

def client_checkout(String git_branch) {
    checkout([
        $class: 'GitSCM',
        branches: [
            [name: "origin/${git_branch}"]
        ],
        doGenerateSubmoduleConfigurations: false,
        extensions: [
            [$class: 'CleanBeforeCheckout'],
            [$class: 'CheckoutOption', timeout: 60],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false, timeout: 60]
        ],
        submoduleCfg: [],
        userRemoteConfigs: [
            [url: 'git@github.com:netSkope/client.git'],
            [url: 'git@github.com:netSkope/npa_core.git']
        ]
    ])
}

def npa_core_checkout(String git_branch) {
    checkout([
        $class: 'GitSCM',
        branches: [
            [name: "origin/${git_branch}"]
        ],
        doGenerateSubmoduleConfigurations: false,
        extensions: [
            [$class: 'CleanBeforeCheckout'],
            [$class: 'CheckoutOption', timeout: 60],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false, timeout: 60]
        ],
        submoduleCfg: [],
        userRemoteConfigs: [
            [url: 'git@github.com:netSkope/npa_core.git']
        ]
    ])
}

def get_default_branch() {
    return  "develop"
}


def github_get(String url) {
    withCredentials([
        string(credentialsId: 'github-token', variable: 'TOKEN')
    ]) {
        def response = httpRequest(
                consoleLogResponseBody: true, acceptType: 'APPLICATION_JSON',
                customHeaders: [
                    [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
                    [maskValue: true, name: 'Authorization', value: 'Token ' + TOKEN]
                ],
                httpMode: 'GET',
                ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
                url: url,
                quiet: false,
                validResponseCodes: "100:599"
                )
        return response
    }
}

def github_post_json(String url, def data) {
    // Pass a dictionary type dict, will convert it to json
    def payload = JsonOutput.toJson(data)
    print(payload)
    withCredentials([
        string(credentialsId: 'github-token', variable: 'TOKEN')
    ]) {
        def response = httpRequest(
                consoleLogResponseBody: true, contentType : 'APPLICATION_JSON',
                customHeaders: [
                    [maskValue: false, name: 'Accept', value: 'application/json'],
                    [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
                    [maskValue: true, name: 'Authorization', value: 'Token ' + TOKEN]
                ],
                httpMode: 'POST',
                quiet: true,
                requestBody : payload.toString(),
                ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
                url: url
                )
        return response
    }
}

def resp_to_json(def response) {
    return new JsonSlurper().parseText(response.content)
}

def is_branch_exists(String branch_name, String repo_name) {
    def base_url = "https://api.github.com/repos/netSkope/"+repo_name+"/branches/"+branch_name
    def response = null
    try {
        withCredentials([
            string(credentialsId: 'github-token', variable: 'TOKEN')
        ]) {
            response = httpRequest(
                    consoleLogResponseBody: true, acceptType: 'APPLICATION_JSON',
                    customHeaders: [
                        [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
                        [maskValue: true, name: 'Authorization', value: 'Token ' + TOKEN]
                    ],
                    httpMode: 'GET',
                    ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
                    url: base_url,
                    validResponseCodes: "200"
                    )
        }
        def response_json = readJSON text: response.content
        if(response_json["name"] == branch_name) return true
    }
    catch( Exception e) {
        return false
    }
    return false
}
def is_same_branch_present(def repo_name, def branch_name, def commit_sha) {
    def base_url = "https://api.github.com/repos/netSkope/"+ repo_name+"/branches/"+branch_name
    def response = null

    try {
        withCredentials([
            string(credentialsId: 'github-token', variable: 'TOKEN')
        ]) {
            response = httpRequest(
                    consoleLogResponseBody: true, acceptType: 'APPLICATION_JSON',
                    customHeaders: [
                        [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
                        [maskValue: true, name: 'Authorization', value: 'Token ' + TOKEN]
                    ],
                    httpMode: 'GET',
                    ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
                    url: base_url,
                    validResponseCodes: "200"
                    )
        }
        def response_json = readJSON text: response.content
        if(response_json["name"] == branch_name && response_json["commit"]["sha"] == commit_sha) return true
    }
    catch( Exception e) {
        return false
    }
    return false
}

def rename_branch(def repo_name, def branch_name, def new_name) {
    println("Renaming a branch from ${branch_name} to ${new_name} in ${repo_name} repository.")
    def base_url = "https://api.github.com/repos/netSkope/"+repo_name+"/branches/"+branch_name+"/rename"
    def payload = [:]
    payload["new_name"] = new_name
    def response = github_post_json(base_url, payload)
    if(response.status == 201) {
        print("Successfully renamed ${branch_name} branch  to ${new_name} in ${repo_name} repository.")
    }
    else {
        error("Failed to rename ${branch_name} branch  to ${new_name} in ${repo_name} repository.")
    }
}

def rename_branch_with_timestamp(def repo_name, def branch_name) {
    String  new_name = branch_name + "-" + System.currentTimeMillis().toString()
    rename_branch(repo_name, branch_name, new_name)
    return new_name
}

def create_branch_from_sha(String branch_name, String repo_name, String commit_sha) {
    def base_url =  "https://api.github.com/repos/netSkope/${repo_name}/git/refs"
    def payload = [:]
    payload.put("ref", "refs/heads/${branch_name}")
    payload.put("sha", commit_sha)
    branch_create = github_post_json(base_url, payload)
    if(branch_create.status == 201) {
        print("Successfully created ${branch_name} branch in ${repo_name} repository.")
        return
    }
    error("Couldn't create ${branch_name} branch in ${repo_name} repository. Response :: \n"+ branch_create.content)
}

def create_branch(String branch_name, String repo_name) {
    def base_url =  "https://api.github.com/repos/netSkope/${repo_name}/git/refs"
    ref_sha = get_last_commit_sha(repo_name, get_default_branch())
    def payload = [:]
    payload.put("ref", "refs/heads/${branch_name}")
    payload.put("sha", ref_sha)
    branch_create = github_post_json(base_url, payload)
    if(branch_create.status == 201) {
        print("Successfully create ${branch_name} branch in ${repo_name} repository.")
        return
    }
    error("Couldn't create ${branch_name} branch in ${repo_name} repository. Response :: \n"+ branch_create.content)
}

def create_branch_if_not_exists(String branch_name, String repo_name) {
    if(!is_branch_exists(branch_name,repo_name)) {
        create_branch(branch_name,repo_name)
    }
}

def create_job (Map jobconfig){
    def job_name = jobconfig.job_name
    def scm_poll_expr = jobconfig.scm_poll_expr
    def concurrent_builds = "concurrent_builds" in jobconfig ? jobconfig.concurrent_builds : false
    def string_params = "string_params" in jobconfig ? jobconfig.string_params : []
    def choice_params = "choice_params" in jobconfig ? jobconfig.choice_params : []
    def boolean_params = "boolean_params" in jobconfig ? jobconfig.boolean_params : []
    def pipeline_src_repo = jobconfig.pipeline_src_repo
    def pipeline_src_branch = jobconfig.pipeline_src_branch
    def pipeline_file_name = jobconfig.pipeline_file_name
    def delete_if_exists = "delete_if_exists" in jobconfig ? jobconfig.delete_if_exists : false
    def pipeline_repo_cred_id = jobconfig.pipeline_repo_cred_id
    def job_access_users = jobconfig.job_access_users.split(",")
    def view_name = jobconfig.view_name

    def permissions = new AuthorizationMatrixProperty()
    for (user_email in job_access_users) {
        permissions.add(Item.CONFIGURE, user_email.trim())
        permissions.add(Item.BUILD, user_email.trim())
        permissions.add(Item.READ, user_email.trim())
    }

    if(delete_if_exists) {
        def job = Jenkins.instance.getItemByFullName(job_name)
        if(job) {
            job.delete()
        }
    }

    // Creation of job
    def newJob = Jenkins.instance.createProject(WorkflowJob.class, job_name)
    newJob.addTrigger(new SCMTrigger(scm_poll_expr))
    newJob.setConcurrentBuild(concurrent_builds)
    newJob.addProperty(permissions)
    //Adding Job Parameters
    def parameter_definitions = []
    for(parameter in string_params) {
        parameter_definitions.add(new StringParameterDefinition(parameter.name, parameter.value, parameter.description))
    }
    for(parameter in choice_params) {
        def value = parameter.value as String[]
        parameter_definitions.add(new ChoiceParameterDefinition(parameter.name, value, parameter.description))
    }
    for(parameter in boolean_params) {
        parameter_definitions.add(new BooleanParameterDefinition(parameter.name, parameter.value, parameter.description))
    }
    if(parameter_definitions.size() >0 ) {
        ParametersDefinitionProperty parametersDefinitionProperty = new ParametersDefinitionProperty(parameter_definitions);
        newJob.addProperty(parametersDefinitionProperty);
    }

    //Configuring Jenkins pipeline settings
    GitStep gitstep = new GitStep(pipeline_src_repo)
    gitstep.setBranch(pipeline_src_branch)
    gitstep.setCredentialsId(pipeline_repo_cred_id)
    CpsScmFlowDefinition cpsdefinition = new CpsScmFlowDefinition(gitstep.createSCM(), pipeline_file_name);
    cpsdefinition.setLightweight(true);
    newJob.setDefinition(cpsdefinition)
    def jenkins = Jenkins.instance

    def existing_views = [:]
    for(view in jenkins.getViews()){
        existing_views.put(view.getViewName().toLowerCase(), view.getViewName())
    }
    def view = null
    if(view_name.toLowerCase() in existing_views) {
        view = jenkins.getView(existing_views.get(view_name.toLowerCase()))
    }

    else {
        view = new hudson.model.ListView(view_name)
        jenkins.addView(view)
    }
    Set job_names = []
    for(job in view.getJobNames()) {
        job_names.add(job)
    }
    job_names.add(job_name)
    view.setJobNames(job_names)

    return newJob.getAbsoluteUrl()
}


def notify_feature_branch_failure() {
    if (is_email_publish_disabled()) {
        return
    }
    rcpt = get_email_list(params.EMAIL_RECIPIENTS)

    result = currentBuild.getCurrentResult()
    def email_subject = "#${BUILD_NUMBER} ${JOB_NAME} ${result}"
    String jobName = "${JOB_NAME}".toString()
    int buildNum = "${BUILD_NUMBER}".toString().toInteger()
    def errorLog = get_logs(jobName, buildNum, 50)// '${BUILD_LOG, maxLines=15}'
    def message_body = """
Hi

You are receiving this mail as your commit may have broken the build. Please review the Build Logs at ${env.RUN_DISPLAY_URL}

Error Log:
${errorLog}

Thanks,
Productivity Engineering Team

    """
    //message = message.replace("Thanks", "\n\t Error Log:\n${errorLog}\n\n\n    Thanks\n Productivity Engineering team")
    emailext body: "${message_body}",
    subject: email_subject,
    to: "${rcpt}"
}

@NonCPS
def kill_job(def job_name, int build_number) {
    Jenkins.instance.getItemByFullName(job_name)
            .getBuildByNumber(build_number)
            .finish(
            hudson.model.Result.ABORTED,
            new java.io.IOException("Aborting build")
            );
}

@NonCPS
def disable_job(name) {
    def job_obj = Jenkins.instance.getItemByFullName(name)
    if(job_obj) {
        job_obj.makeDisabled(true)
    }
}

def get_dp_branch() {
    if (env.DP_BRANCH) {
        return "${env.DP_BRANCH}".trim()
    }
    if (is_release_job()) {
        return "${env.RELEASE_BRANCH}".trim()
    }
    return "develop"
}

// for handling pre-R89 and R89+
// checkouts differently
def is_pre89_branch() {
    if (is_release_job()) {
        def num = "${env.RELEASE_BRANCH}".find( /\d+/ )?.toInteger()
        if ( num != null && num < 89 ) {
            return true
        }
        /*
         * num returns null if the RELEASE_BRANCH is clickhouse-*, iaas-* for component builds
         * In such case don't perform git checkout for dataplane. Instead let the service submodule checkout handle dataplane.
         */
        // if(!"${RELEASE_BRANCH}".toString().startsWith("Release")){
        //   return true
        // }
    }
    return false
}

def get_webui_branch() {
    if (env.WEBUI_BRANCH) {
        return "${env.WEBUI_BRANCH}".trim()
    }
    if (is_release_job()) {
        return "${env.RELEASE_BRANCH}".trim()
    }
    return "develop"
}

// for handling pre-94 and R94+
// checkouts differently
def is_pre94_branch() {
    if (is_release_job()) {
        def num = "${env.RELEASE_BRANCH}".find( /\d+/ )?.toInteger()
        if ( num != null && num < 94 ) {
            return true
        }
    }
    return false
}

def github_put_json(String url, def data) {
    // Pass a dictionary type dict, will convert it to json
    def payload = JsonOutput.toJson(data)
    print(payload)
    withCredentials([
        string(credentialsId: 'github-token', variable: 'TOKEN')
    ]) {
        def response = httpRequest(
                consoleLogResponseBody: true, contentType : 'APPLICATION_JSON',
                customHeaders: [
                    [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
                    [maskValue: true, name: 'Authorization', value: 'Token ' + TOKEN]
                ],
                httpMode: 'PUT',
                quiet: false,
                requestBody : payload.toString(),
                ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
                url: url
                )
        return response
    }
}

def get_git_modules(String repo,String branch, String filename) {
    String  url="https://api.github.com/repos/netSkope/"+repo+"/contents/"+filename
    println("url:" + url)
    def response = null
    try {
        response = github_get(url+"?ref="+branch)
    } catch( Exception e) {
        println("Failed to get the file ${url} from branch ${branch}")
        throw e
    }
    def response_json = readJSON text: response.content
    if(!response_json["content"]) {
        error("Content in not present for ${url} from branch ${branch}")
    }
    if(response_json["content"] == null) {
        error("Content in the file ${url} from branch ${branch} is null")
    }
    def git_modules_content = new String(response_json["content"].decodeBase64())
    return git_modules_content
}

// push_to_github updates content of a file in github.
def push_to_github(String filename, String repo, String branch, String content, String commit_message = "") {
    url="https://api.github.com/repos/netSkope/"+repo+"/contents/"+filename
    println("url:" + url)
    def response =github_get(url+"?ref="+branch)
    def data = new groovy.json.JsonSlurperClassic().parseText(response.content)
    sha=data['sha']
    println("data from get:" + data)
    def payload = [:]
    payload.put("message", commit_message)
    payload.put("branch", branch)
    payload.put("content", content.bytes.encodeBase64().toString())
    payload.put("sha", sha)
    payload.put("accept", "application/vnd.github.v3+json")
    println(payload)
    def resp=github_put_json(url, payload)
    print("resp:" + resp)
}

def get_submodule(String module, String param) {
    println("Looking for module: $module and param: $param")
    def exists = fileExists '.gitmodules'
    if (!exists) {
        println(".gitmodules not found")
        return ""
    }
    content = readFile '.gitmodules'
    println("Found .gitmodules:\n$content")
    def section;
    lines = content.split('\n')
    for (line in lines) {
        if (line != null) {
            line = line.trim()
            if (line.startsWith("[submodule ")) {
                section = line.split(" ")[-1].replace("]", '').replace('"', '')
            } else if (section.equals(module) && line.find("=") != -1) {
                if (line.startsWith(param)) {
                    val = line.split("=")[1].trim()
                    println("Found $module with $param = $val")
                    return val
                }
            }
        }
    }
    println("module not found: $module")
    return ""
}

@NonCPS
def get_parameters() {
    def master_build_number = "${BUILD_NUMBER}".toInteger()
    def master_job = Jenkins.instance.getItemByFullName("${JOB_NAME}").getBuildByNumber(master_build_number)
    def parameters_action = master_job.getAction(hudson.model.ParametersAction.class)
    if (parameters_action) {
        return parameters_action.getAllParameters()
    }
    return null
}
def rebuild(def master_job, def master_build_number) {
    def build_params = []
    def parameters = get_parameters()
    if (parameters !=null){
        for (parameter in parameters) {
            if (parameter instanceof StringParameterValue){
                build_params.add(string(name: parameter.getName(), value: parameter.getValue()))
            }
            else if(parameter instanceof BooleanParameterValue){
                build_params.add(booleanParam(name: parameter.getName(), value: parameter.getValue().toBoolean()))
            }
        }
        build job: "${JOB_NAME}", parameters: build_params, propagate: false, wait: false
    }
    else {
        build job: "${JOB_NAME}",  propagate: false, wait: false
    }
}

def get_metric(String metric, String exit_status) {
    def response = httpRequest(
            consoleLogResponseBody: true,
            ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
            httpMode: 'GET',
            quiet: true,
            url: "https://metrics.netskope.io/push/api/v1/metrics"
            )
    println(response.status)
    println(response.getClass())
    println(response.content)
    try {
        def data = new groovy.json.JsonSlurper().parseText(response.getContent())["data"]
        for (metric_data in data) {
            if (metric_data["labels"]["job"] == "${env.JOB_NAME}" && metric_data["labels"]["exit_code"] == exit_status && metric_data[metric] != null) {
                metric_val = metric_data[metric]["metrics"][0]["value"]
                if (metric_val.indexOf("e") != -1) return new BigDecimal(metric_val).toBigInteger()
                return metric_val as Long
            }
        }
    } catch (Exception e) {
        println("failed to get_metric:" + metric)
        println(response)
        println("Status: " + response.status)
        println("response content:" + response.getContent())
        printStackTrace(e)
        return null  // Crash this instead of return 0 which will reset our counters.
    }
    return response
}

def collect_metrics(Long start_time, String exit_code) {
    try {
        println("collect_metrics:start_time:" + start_time)
        duration = new Date().getTime() - start_time
        println("collect_metrics:" + duration)
        count = get_metric("pipeline_run_count", exit_code) + 1
        duration_total = get_metric("pipeline_run_duration_total", exit_code) + duration
        def url = "https://metrics.netskope.io/push/metrics/job/${env.JOB_NAME}/"
        println(url)
        payload = "# TYPE pipeline_run_time gauge\npipeline_run_time{exit_code=\"" + exit_code + "\", instance=\"" + "${env.BUILD_ID}" + "\"} " + duration + "\n"
        println(payload)
        def response = httpRequest(
                consoleLogResponseBody: true,
                quiet: true,
                customHeaders: [
                    [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
                    [maskValue: false, name: 'Accept', value: 'application/octet-stream'],
                ],
                ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
                httpMode: 'POST',
                contentType: 'APPLICATION_OCTETSTREAM',
                requestBody : payload.toString(),
                url: url
                )
        url = "https://metrics.netskope.io/push/metrics/job/${env.JOB_NAME}/exit_code/" + exit_code
        println(url)
        payload = "# TYPE pipeline_run_count counter\npipeline_run_count{} " + count + "\n" +
                "# TYPE pipeline_run_duration_total counter\npipeline_run_duration_total{} " + duration_total + "\n"
        println(payload)
        response = httpRequest(
                consoleLogResponseBody: false,
                customHeaders: [
                    [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
                    [maskValue: false, name: 'Accept', value: 'application/octet-stream'],
                ],
                ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
                httpMode: 'POST',
                contentType: 'APPLICATION_OCTETSTREAM',
                requestBody : payload.toString(),
                url: url
                )
    } catch (Exception e) {
        println("failed to collect_metrics")
        println(e)
        printStackTrace(e)
        return false
    }
    return true
}

def ministack_collect_metrics(Long start_time, String exit_code, String service_version = "", String user = "", String category = "MINISTACK_INFRA", String stack_name = "") {
    try {

        println("collect_metrics:start_time:" + start_time)
        duration = new Date().getTime() - start_time
        println("collect_metrics:" + duration)
        count = 1
        def url = "https://metrics.netskope.io/push/metrics/job/${env.JOB_NAME}/"
        println(url)
        payload = "# TYPE ministack_run_time gauge\nministack_run_time{exit_code=\"" + exit_code + "\", instance=\"" + "${env.BUILD_ID}" + "\", stack_name=\"" + stack_name + "\"} " + duration + "\n"
        println(payload)
        def response = httpRequest(
                consoleLogResponseBody: true,
                quiet: true,
                customHeaders: [
                    [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
                    [maskValue: false, name: 'Accept', value: 'application/octet-stream'],
                ],
                ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
                httpMode: 'PUT',
                contentType: 'APPLICATION_OCTETSTREAM',
                requestBody : payload.toString(),
                url: url
                )
        url = "https://metrics.netskope.io/push/metrics/job/${env.JOB_NAME}/exit_code/" + exit_code + "/service_version/" + URLEncoder.encode(service_version) + "/user/" + URLEncoder.encode(user)
        println(url)
        payload = "# TYPE ministack_run_count counter\nministack_run_count{exit_code=\"" + exit_code + "\", instance=\"" + "${env.BUILD_ID}" + "\", stack_name=\"" + stack_name + "\", category=\"" + category + "\"} " + count + "\n"

        println(payload)
        response = httpRequest(
                consoleLogResponseBody: false,
                customHeaders: [
                    [maskValue: false, name: 'User-Agent', value: 'Jenkins'],
                    [maskValue: false, name: 'Accept', value: 'application/octet-stream'],
                ],
                ignoreSslErrors: true, responseHandle: 'LEAVE_OPEN',
                httpMode: 'PUT',
                contentType: 'APPLICATION_OCTETSTREAM',
                requestBody : payload.toString(),
                url: url
                )
    } catch (Exception e) {
        println("failed to collect_metrics")
        println(e)
        printStackTrace(e)
        return false
    }
    return true
}

def set_downstrem_jobs_build_number(def down_stream_jobs){

    master_build_number = "${BUILD_NUMBER}".toInteger()
    child_build_numbers = []
    for (down_stream_job in down_stream_jobs) {
        child_build_numbers.add(Jenkins.instance.getItemByFullName(down_stream_job).getNextBuildNumber())
    }
    if (master_build_number < child_build_numbers.max() ) {
        currentBuild.description = "Killing the master and downstream jobs's build number mismatched"
        Jenkins.instance.getItemByFullName("${JOB_NAME}").updateNextBuildNumber(child_build_numbers.max())
        def master_job = Jenkins.instance.getItemByFullName("${JOB_NAME}").getBuildByNumber(master_build_number)
        rebuild(master_job, master_build_number)
        Jenkins.instance.getItemByFullName("${JOB_NAME}").getBuildByNumber(master_build_number).doKill()
    }
    else {
        for (down_stream_job in down_stream_jobs) {
            Jenkins.instance.getItemByFullName(down_stream_job).updateNextBuildNumber(master_build_number)
        }
    }
}

def add_os_suffix(build_args) {
    if ( is_release_job() ) {
        def version = ''
        if ( params.containsKey("BUILD_TAG") && "${BUILD_TAG}" != "" ) {
            // stork release/hotfix check
            version = "$BUILD_TAG"
        } else if ( params.containsKey("COMPONENT") && "${COMPONENT}" != "" ) {
            // stork-service-components job
            if (params.containsKey("PKG_VERSION") && "${PKG_VERSION}" != '') {
                version = "$PKG_VERSION"
            }
        }
        if ((version != '' && "$version".split('\\.')[0].toInteger() >= 92) || (env.USE_EP_VERSIONER && env.USE_EP_VERSIONER.toBoolean())) {
            build_args += " CUSTOM_OS_SUFFIX=-${STORK_BASE_IMAGE_SUFFIX} "
        }
    }
    println "DEBUG: build_args: " + build_args
    return build_args
}

def invoke_command_with_retry(String cmd, int num_times = 2, int sleep_time = 10, String sleep_unit = "SECONDS") {
    println("Execute the command ${cmd} with ${num_times} retries")
    def retryAttempt = 0
    retry(num_times) {
        sleep_time = sleep_time > 0 ? sleep_time : 0
        if(retryAttempt > 0 && sleep_time > 0) {
            sleep_time = sleep_time * retryAttempt
        }
        retryAttempt = retryAttempt + 1
        try {
            sh cmd
        } catch(Exception ex) {
            println("Waiting for ${sleep_time} ${sleep_unit} before retry")
            if(sleep_time > 0) {
                sleep(time: sleep_time, unit: sleep_unit)
            }
            throw ex
        }
    }
}

def copy_test_suite_logs_to_nfs() {
    sh """
        mkdir -p /var/www/html/test-suite-logs/${JOB_NAME}/${BUILD_NUMBER}/
        cp ${NS_BUILD_DIR}/obj/test-suite.log /var/www/html/test-suite-logs/${JOB_NAME}/${BUILD_NUMBER}/
    """
    println("Test Logs: ${UT_FQDN}/test-suite-logs/${JOB_NAME}/${BUILD_NUMBER}/test-suite.log")
}

def process_pip_config(def pypi_urls) {
    sh "pip config  --global get global.index-url"
    String index_url = sh(returnStdout: true, script:'pip config  --global get global.index-url')
    def index_urls = index_url.split("\n") as List

    for (url in index_urls) {
        url = url.trim()
        if (!url.isEmpty()) {
            pypi_urls["INDEX"] = url
        }
    }
    // This try-catch block added to handle condition
    // when no extra index url present in pip config
    try {
        sh "pip config  --global get global.extra-index-url"
        String extra_index_url = sh(returnStdout: true, script:'pip config  --global get global.extra-index-url')
        def extra_index_urls = extra_index_url.split("\n") as List
        def extra_indexes = []
        for (url in extra_index_urls) {
            url = url.trim()
            if (!url.trim().isEmpty()) {
                extra_indexes.add(url)
            }
        }
        pypi_urls["EXTRA_INDEX"] = extra_indexes.join(",")
        println("Added url for extra index ...")
    } catch (Exception e) {
        println(e)
        println("Extra index url is not present in builder, Ignoring this error ...")
    }
    print(pypi_urls)
}

def run_eparwen(String cmd) {
    sh '''#!/usr/bin/env bash

    ARWEN_VERSION=23.86.1

    if [[ "$(ep-arwen version 2>/dev/null)" == *"$ARWEN_VERSION"* ]]; then
    ARWEN=ep-arwen
    else
    PLATFORM=$(echo $(uname -s)_$(uname -m) | tr '[:upper:]' '[:lower:]')
    ARWEN_DIR=ep-tools/ep-arwen/$ARWEN_VERSION/$PLATFORM
    ARWEN=$ARWEN_DIR/ep-arwen

    if [ ! -e .$ARWEN ]; then
        NS_ARTIFACTORY_URL=https://artifactory-rd.netskope.io/artifactory
        mkdir -p .$ARWEN_DIR
        curl -o .$ARWEN $NS_ARTIFACTORY_URL/$ARWEN
        chmod 755 .$ARWEN
    fi

    ARWEN=.${ARWEN}
    fi

    echo $($ARWEN ''' + cmd + ''')'''
}

def install_jfrog_cli (
        def server_id = null
) {

    if(server_id == null) {
        server_id = get_default_artifactory()
    }
    println "DEBUG: server_id: " + server_id

    def rtf_url = get_rtf_url(server_id)
    def rtf_rd_url = get_rtf_url('artifactory-rd')
    println "DEBUG: rtf_url: " + rtf_url

    println("Installing JFrog CLI v2.22.0 in ${rtf_url} ...")
    sh '''
      curl -fL https://install-cli.jfrog.io | sh -s -- 2.22.0
      sudo chown nsadmin:nsadmin /usr/local/bin/jf
    '''
    withCredentials([
        usernamePassword(credentialsId: 'ARTIFACTORY_DOCKER_LOGIN', \
      passwordVariable: 'ART_PASS', usernameVariable: 'ART_USER')
    ]) {
        sh """
        jf c add artifactory --interactive=false --artifactory-url=${rtf_url} \
          --user=${ART_USER} --password=${ART_PASS}
        jf c add artifactory-rd --interactive=false --artifactory-url=${rtf_rd_url} \
          --user=${ART_USER} --password=${ART_PASS}
        jf config show
      """
    }
}

// Checkout a tag or a branch based on rollback flags
def checkout_branch_or_tag(String remote, String branchOrTag) {
    if (env.ROLLBACK_BUILD && env.ROLLBACK_BUILD == 'true' && env.LKG_BUILD_TAG) {
        // this is a rollback build - check out from tag
        git_checkout_tag(remote, branchOrTag, true, false, 120, "github-https")
    }
    else {
        // this is NOT a rollback build - check out from branch
        git_check_out(remote, branchOrTag, true, false, 120, false, "github-https")
    }
}

def generate_stork_component_build_queue(def parllel_targets, boolean build_commons, String stage_name, def build_targets, def build_command_args, int queue_id, def total_build_images, def build_info) {
    parllel_targets[stage_name] = {
        node("${AGENT_LABEL}") {
            stage("${stage_name} Checkout") {
                script {
                    dir("$NS_BUILD_DIR") {
                        run_eparwen("all post-build")
                        git.set_credentials()
                        install_jfrog_cli()
                        git_check_out("${GIT_REMOTE}".toString(), "${SERVICE_COMMIT_SHA}".toString(), true, false, 120, true, "github-https", true, true)
                        clean_submodule_dirs()
                        dir("$NS_BUILD_DIR/dataplane/src") {
                            git_check_out("${DATAPLANE_URL}".toString(), "${DATAPLANE_COMMIT_SHA}".toString(), true, false, 120, true, "github-https", true, true)
                        }
                        clean_webui_dir()
                        dir("$NS_BUILD_DIR/components/webui") {
                            git_check_out("${WEBUI_URL}".toString(), "${WEBUI_COMMIT_SHA}".toString(), true, false, 120, true, "github-https", true, true)
                        }
                    }
                }
            }
            stage("${stage_name} common libs") {
                script {
                    invoke_command_with_retry("make update ")
                    if (build_commons) {
                        invoke_command_with_retry("make -j 12 publish-common-packages ${build_command_args}")
                    }
                    invoke_command_with_retry("make -j 12 libs ${build_command_args}")
                }
            }
            stage("${stage_name} build components") {
                script {
                    def sub_targets = [: ]
                    for (target in build_targets) {
                        def sub_target = target
                        sub_targets[sub_target] = {
                            dir("${NS_BUILD_DIR}/components/${sub_target}") {
                                invoke_command_with_retry("make stork-parallel-build-all ${build_command_args }")
                                def images_as_str = sh(script: "docker images", returnStdout: true)
                                def docker_log = sh (script: "journalctl -u docker.service", returnStdout: true)
                                echo "[Queue-${queue_id}]: Images built ${images_as_str} after successfully building [${sub_target}]"
                                echo "[Queue-${queue_id}]: Docker log ${docker_log} after successfully building [${sub_target}]"
                            }
                        }
                    }
                    parallel sub_targets
                }
            }
            stage("${stage_name} publish images") {
                script {
                    push_images_for_multinode_builds(
                            "${RTF_SRC}",
                            "${RTF_REPOSITORY}".toString(),
                            5,
                            true,
                            total_build_images,
                            build_info
                            )
                }
            }
        }
    }
}



def generate_stork_component_build_queue_on_master(def parllel_targets, boolean build_commons, String stage_name, def build_targets, def build_command_args, int queue_id, def total_build_images, def build_info) {
    parllel_targets[stage_name] = {
        stage("${stage_name} common libs") {
            script {
                invoke_command_with_retry("make update ")
                if (build_commons) {
                    invoke_command_with_retry("make -j 12 publish-common-packages ${build_command_args}")
                }
                invoke_command_with_retry("make -j 12 libs ${build_command_args}")
            }
        }
        stage("${stage_name} build components") {
            script {
                def sub_targets = [: ]
                for (target in build_targets) {
                    def sub_target = target
                    sub_targets[sub_target] = {
                        dir("${NS_BUILD_DIR}/components/${sub_target}") {
                            invoke_command_with_retry("make stork-parallel-build-all ${build_command_args }")
                            def images_as_str = sh(script: "docker images", returnStdout: true)
                            def docker_log = sh (script: "journalctl -u docker.service", returnStdout: true)
                            echo "[Queue-${queue_id}]: Images built ${images_as_str} after successfully building [${sub_target}]"
                            echo "[Queue-${queue_id}]: Docker log ${docker_log} after successfully building [${sub_target}]"
                        }
                    }
                }
                parallel sub_targets
            }
        }
        stage("${stage_name} publish images") {
            script {
                push_images_for_multinode_builds(
                        "${RTF_SRC}",
                        "${RTF_REPOSITORY}".toString(),
                        2,
                        true,
                        total_build_images,
                        build_info
                        )
            }
        }
    }
}


def generate_stork_component_config_build_queue(def parllel_targets, boolean build_commons, String stage_name, def build_command_args, int queue_id, def total_build_images, def build_info) {
    parllel_targets[stage_name] = {
        node("${AGENT_LABEL}") {
            stage("${stage_name} Checkout") {
                script {
                    dir("$NS_BUILD_DIR") {
                        run_eparwen("all post-build")
                        git.set_credentials()
                        install_jfrog_cli()
                        git_check_out("${GIT_REMOTE}".toString(), "${SERVICE_COMMIT_SHA}".toString(), true, false, 120, true, "github-https", true, true)
                        clean_submodule_dirs()
                        dir("$NS_BUILD_DIR/dataplane/src") {
                            git_check_out("${DATAPLANE_URL}".toString(), "${DATAPLANE_COMMIT_SHA}".toString(), true, false, 120, true, "github-https", true, true)
                        }
                        clean_webui_dir()
                        dir("$NS_BUILD_DIR/components/webui") {
                            git_check_out("${WEBUI_URL}".toString(), "${WEBUI_COMMIT_SHA}".toString(), true, false, 120, true, "github-https", true, true)
                        }
                    }
                }
            }
            stage("${stage_name} common libs") {
                script {
                    invoke_command_with_retry("make update ")
                    if (build_commons) {
                        invoke_command_with_retry("make -j 12 publish-common-packages ${build_command_args}")
                    }
                    invoke_command_with_retry("make -j 12 libs ${build_command_args}")
                    invoke_command_with_retry("make stork-tool ${build_command_args}")
                    dir("${NS_BUILD_DIR}/components/cfgagent") {
                        invoke_command_with_retry("make generate-role-definition ${build_command_args} ")
                    }
                }
            }
            stage("Validate stack configs") {
                script {
                    dir("${NS_BUILD_DIR}") {
                        invoke_command_with_retry("make stork-validate-stack-configs ${build_command_args}")
                    }
                }
            }
            stage("${stage_name} build components") {
                script {
                    dir("${NS_BUILD_DIR}/components") {
                        invoke_command_with_retry("make stork-config SKIP_COMPONENTS=${SKIPCOMPONENTS} ${build_command_args}")
                        def images_as_str = sh(script: "docker images", returnStdout: true)
                        def docker_log = sh (script: "journalctl -u docker.service", returnStdout: true)
                        echo "[Queue-${queue_id}]: Images built ${images_as_str} after successfully building [stork config images]"
                        echo "[Queue-${queue_id}]: Docker log ${docker_log} after successfully building [stork config images]"
                    }
                }
            }
            stage("${stage_name} add labels") {
                script {
                    dir("${NS_BUILD_DIR}/scripts/foundation-labels-update") {
                        if (fileExists("update_labels_foundation_images.py")) {
                            // Setup Virtual environment.
                            sh "python3.8 -m venv ./venv"
                            sh "./venv/bin/python -m pip install --no-deps -r ./requirements.txt"

                            sh "./venv/bin/python update_labels_foundation_images.py"
                        }
                        else {
                            println("Updating labels for foundation images is not supported for releases older than 106.")
                        }
                    }
                }
            }
            stage("${stage_name} publish images") {
                script {
                    push_images_for_multinode_builds(
                            "${RTF_SRC}",
                            "${RTF_REPOSITORY}".toString(),
                            5,
                            true,
                            total_build_images,
                            build_info
                            )
                }
            }
        }
    }
}

// Fetch the module_id's commit sha
def get_sha_from_manifest(String module_id, String branch) {
    def commit_sha = ""
    def response = null
    def parsedJson = null
    def manifestv1_url = "${env.MANIFEST_SERVICE_URL_STAGING}"
    def manifestv2_url = "${env.MANIFEST_SERVICE_V2_URL}"

    fetch_v2_failed = false
    println("Fetch manifest v2")
    def url = "${manifestv2_url}/v1/manifestserv/latest_module_name/${module_id}?branchName=${branch}"
    try {
        // If response code >= 400, it will throw exception.
        response = httpRequest(
                consoleLogResponseBody: true,
                contentType: 'APPLICATION_JSON',
                url: url
                )
        parsedJson = new groovy.json.JsonSlurper().parseText(response.content)
        commit_sha = parsedJson.get("manifest").get("gitCommitInfo").get("lastCommitSha")
    } catch (Exception e) {
        fetch_v2_failed = true
        def error_msg = "Failed to fetch latest module for '${module_id}/${branch}' in ${manifestv2_url}"
        send_slack_msg("#dataplane-manifest-v2-migration-alert", error_msg.toString())
        println(error_msg)
    }

    if (fetch_v2_failed) {
        println("Fetch manifest v1")
        url = "${manifestv1_url}/v1/manifestserv/latest_module_id/${module_id}?branchName=${branch}"
        try {
            response = httpRequest(
                    consoleLogResponseBody: true,
                    contentType: 'APPLICATION_JSON',
                    url: url,
                    ignoreSslErrors: true,
                    wrapAsMultipart: false
                    )
        } catch (Exception e) {
            println("Failed to fetch latest module for ${module_id} in " + manifestv1_url)
            println("Please check https://ci-drone.netskope.io/netSkope/${module_id} for ${branch} manifest")
            throw e
        }
        parsedJson = new groovy.json.JsonSlurper().parseText(response.content)
        commit_sha = parsedJson.get("gitMetadata").get("lastCommitSha")
    }
    println("COMMIT SHA ::: " + commit_sha)
    return "${commit_sha}"
}

// Fetch the module_id's pkg version
def get_version_from_manifest(String module_id, String branch) {
    def pkg_version = ""
    def response = null
    def parsedJson = null
    def manifestv1_url = "${env.MANIFEST_SERVICE_URL_STAGING}"
    def manifestv2_url = "${env.MANIFEST_SERVICE_V2_URL}"

    fetch_v2_failed = false
    println("Fetch manifest v2")
    def url = "${manifestv2_url}/v1/manifestserv/latest_module_name/${module_id}?branchName=${branch}"
    try {
        // If response code >= 400, it will throw exception.
        response = httpRequest(
                consoleLogResponseBody: true,
                contentType: 'APPLICATION_JSON',
                url: url
                )
        parsedJson = new groovy.json.JsonSlurper().parseText(response.content)
        pkg_version = parsedJson.get("manifest").get("name").split('-')[1]
    } catch (Exception e) {
        fetch_v2_failed = true
        def error_msg = "Failed to fetch latest module for '${module_id}/${branch}' in ${manifestv2_url}"
        send_slack_msg("#dataplane-manifest-v2-migration-alert", error_msg.toString())
        println(error_msg)
    }

    if (fetch_v2_failed) {
        println("Fetch manifest v1")
        url = "${manifestv1_url}/v1/manifestserv/latest_module_id/${module_id}?branchName=${branch}"
        try {
            response = httpRequest(
                    consoleLogResponseBody: true,
                    contentType: 'APPLICATION_JSON',
                    url: url,
                    ignoreSslErrors: true,
                    wrapAsMultipart: false
                    )
        } catch (Exception e) {
            println("Failed to fetch latest module for ${module_id} in " + manifestv1_url)
            println("Please check https://ci-drone.netskope.io/netSkope/${module_id} for ${branch} manifest")
            throw e
        }
        parsedJson = new groovy.json.JsonSlurper().parseText(response.content)
        pkg_version = parsedJson.get("manifestName").split('-')[1]
    }
    println("PKG VERSION ::: " + pkg_version)
    return "${pkg_version}"
}

// Load the specified EP tool from Artifactory into the local directory
def load_ep_tool(String name, String version) {
    String platform = sh(
            script: "echo \$(uname -s)_\$(uname -m) | tr '[:upper:]' '[:lower:]'",
            returnStdout: true
            ).trim()
    String tool_dir = "ep-tools/${name}/${version}/${platform}"
    String tool_path = "${tool_dir}/${name}"
    String artifactory_url = get_rtf_url(get_rd_artifactory())

    sh """#!/usr/bin/env bash

    if [ ! -e ${tool_path} ]; then
        mkdir -p ${tool_dir}
        echo "Downloading ${name} from ${artifactory_url}${tool_path}"
        curl -o ${tool_path} ${artifactory_url}${tool_path}
        chmod 755 ${tool_path}
        ls -al ${tool_path}
    fi"""

    String tool_abs_path = "${NS_BUILD_DIR}/${tool_path}"
    return tool_abs_path
}

// Gets the service version for the given repo using the EP versioning standard
def get_service_version(String repo_path) {
    String versioner_path = load_ep_tool('ep-versioner', env.EP_VERSIONER_VERSION)
    String version

    dir(repo_path) {
        // Calculate the version string, which has a side effect of shallow fetching
        // more commit history to calculate the version string
        version = sh(
                script: "${versioner_path} calc --deepen",
                returnStdout: true
                ).trim()
    }

    return version
}

/*----------------------------------------------------------------------------------------------------
 FOLLOWING BLOCK OF FUNCTIONS TO VALIDATE BUILD
 ARTIFACTS WITH PREVIOUSLY VALIDATED BUILD ARTIFACTS
 */

def create_json_filename (def context) {
    /*
     * create_json_filename form a json file name using context.
     * @param context: list of context
     * @return: json filename
     */
    def filename_string = "${JOB_NAME}"
    for (variable in context) {
        if (variable == "") {
            filename_string += "_none"
        } else {
            filename_string += "_" + variable
        }
    }
    filename_string = filename_string + ".json"
    json_file = filename_string.replace("-", "_")
    println "DEBUG: json file: " + json_file
    return json_file
}

def artifacory_operation(def operation, def file_with_path) {
    /*
     * artifacory_operation function used for fire jfrog cli command.
     * @param operation: Type of operation ie. download, upload, delete
     * @param file_with_path: parameter to be passed as file to be upload, download, delete
     * @return: status of operation, 1=success, 2=failure
     */

    def server_id = null
    if (operation == "download") {
        println "configure artifactory for download:- artifactory-rd"
        op = "dl"
        server_id = "artifactory-rd"
    } else if(operation == "upload") {
        println "configure artifactory for upload:- artifactory"
        op = "u"
        server_id = "${RTF_SRC}"
    } else if (operation == "delete") {
        println "configure artifactory for delete:- artifactory"
        op = "del"
        server_id = "${RTF_SRC}"
    }
    def output = ''
    output = sh(returnStdout: true, script: "jf rt "+ op + " --server-id ${server_id} " + file_with_path).trim()
    def object = readJSON text: output
    def status = object['totals']['success'].toString()
    return status
}

def compare_list(def current_list, def previous_list) {
    /*
     * compare_list function used to compare two list(artifacts).
     * @param current_list: list fetched from current build
     * @param previous_list: list fetched from artifactory for prev build
     * @return: map with missing artifacts, newly added artifacts
     */

    def compare_artifacts_map = [:]
    if (current_list.sort() == previous_list.sort()) {
        println "DEBUG: Artifacts produced in ${JOB_NAME} #(${BUILD_NUMBER}) is Matching with artifacts from previous builds: " + current_list
        return compare_artifacts_map
    } else {
        def missing_artifacts = [:]
        def added_artifacts = [:]
        missing_artifacts = previous_list - current_list
        println "DEBUG: Artifacts missing in current build ${BUILD_NUMBER}: " + missing_artifacts
        compare_artifacts_map.put("missing_artifacts", missing_artifacts)

        added_artifacts = current_list - previous_list
        println "DEBUG: New artifacts added in ${JOB_NAME} compare to previous build: " + added_artifacts
        compare_artifacts_map.put("added_artifacts", added_artifacts)
        return compare_artifacts_map
    }
}

def artifactory_build_validation(def current_artifacts_list, def context) {
    /*
     * artifactory_build_validation function used for build validation based on list of artifacts.
     * @param current_artifacts_list: artifacts collected from current build
     * @param context: list of different context of build, which will uniquely identify json
     * @return: status of comparison, if artifacts are missing = FAIL, if artifacts are added = PASS with warning
     */

    def repository = "build-validation-json"
    json_file = create_json_filename(context)
    println "Download json from artifactory if exists ..."
    status = artifacory_operation("download", "build-validation-json/" + json_file)
    def BUILD_VALIDATION = true
    def channel_name = "one-click-build"

    // CHECK IF ARTIFACTORY LIST PRESENT IN ARTIFACTORY
    if (status == "0") {
        println "DEBUG: File is not present at artifactory: " + json_file
        writeJSON file: json_file, json: current_artifacts_list
        status = artifacory_operation("upload", json_file +" build-validation-json/" + json_file)
        status_backup = artifacory_operation("upload", json_file +" build-validation-json/" + json_file +".${BUILD_NUMBER}")
        if (status == '1' && status_backup == '1') {
            println "Created json file with artifacts list and Successfully uploaded in artiactory ..."
            println "Artifacts list in current build: " + current_artifacts_list
            println "Number of Current builds artifacts: " + current_artifacts_list.size()
        } else {
            println "Json "+ json_file +" is not uploaded on artifactory => FAILED"
            BUILD_VALIDATION = false
        }
        return "${BUILD_VALIDATION}"
    } else {
        println "DEBUG: Artifacts list of ${JOB_NAME} with context "+ context +" context present on artifactory"
        filename = "${WORKSPACE}/${json_file}"
        def downloaded_artifacts_list = readJSON file: filename
        println "Artifacts list in current build: " + current_artifacts_list
        println "Number of Current builds artifacts: " + current_artifacts_list.size()
        println "Artifacts fetched from artifactory: " + downloaded_artifacts_list
        println "Number of artifacts from json fetched from artifactory: " + downloaded_artifacts_list.size()
        def slackMessage = ''
        compare_artifacts_map = compare_list(current_artifacts_list, downloaded_artifacts_list)
        if (compare_artifacts_map.size() != 0) {
            println "DEBUG: Artifacts produced in ${JOB_NAME} build number (${BUILD_NUMBER}) is NOT matching with artifacts from previous builds"
            if(compare_artifacts_map["missing_artifacts"].size() != 0) {
                def missing_artifacts = compare_artifacts_map["missing_artifacts"]
                println "Artifacts are missing in ${JOB_NAME} Build number ${BUILD_NUMBER} from previous builds => FAILED"
                BUILD_VALIDATION = false
                slackMessage =  """@here\n*`ALERT!!!`*\nPlease check following details of artifacts validation\n*Job*: <${BUILD_URL}|*${JOB_NAME}*> #${BUILD_NUMBER}\n*Current artifacts count*: ${current_artifacts_list.size()}\n*Missing Artifacts*: ${missing_artifacts}\n*STATUS*: *`FAILURE`*"""
                slackSend color : '#d94c3a', channel: channel_name, message:slackMessage
            }

            if(compare_artifacts_map["added_artifacts"].size() != 0) {
                def added_artifacts = compare_artifacts_map["added_artifacts"]
                println "Upload current artifacts json on artifactory and backup same file tagging with build number"
                writeJSON file: json_file, json: current_artifacts_list
                def slack_msg_status = ''
                // Checking if new artifacts produced
                if (BUILD_VALIDATION != false) {
                    //delete existing json from artifactory
                    if (artifacory_operation("delete", "build-validation-json/" + json_file) != '1') {
                        println "FAILED: Issue with deleting existing json: " + json_file
                        BUILD_VALIDATION = false
                    }
                    //upload new json to artifactory
                    if (artifacory_operation("upload", json_file +" build-validation-json/" + json_file) != '1') {
                        println "FAILED: Issue with upload json: " + json_file
                        BUILD_VALIDATION = false
                    }
                    //upload json backup with build number to artifactory
                    if (artifacory_operation("upload", json_file +" build-validation-json/" + json_file +".${BUILD_NUMBER}")) {
                        println "FAILED: Issue with upload json backup from current build: " + json_file+".${BUILD_NUMBER}"
                        BUILD_VALIDATION = false
                    }
                    slack_msg_status = "PASSED with Warnings !!!"
                    slackMessage =  """@here\n*`ALERT!!!`*\nPlease check following details of artifacts validation\n*Job*: <${BUILD_URL}|*${JOB_NAME}*> #${BUILD_NUMBER}\n*Current artifacts count*: ${current_artifacts_list.size()}\n*Newly added Artifacts*: ${added_artifacts}\nSTATUS: *`${slack_msg_status}`*\n\n`Updated reference list in artifactory. Please verify newly added artifacts !!!`"""
                } else {
                    // Checking if new artifacts produced with missing artifacts
                    slack_msg_status = "FAILURE"
                    slackMessage =  """@here\n*`ALERT!!!`*\nPlease check following details of artifacts validation\n*Job*: <${BUILD_URL}|*${JOB_NAME}*> #${BUILD_NUMBER}\n*Current artifacts count*: ${current_artifacts_list.size()}\n*Newly added Artifacts*: ${added_artifacts}\nSTATUS: *`${slack_msg_status}`*"""
                }
                println "New artifacts produced in ${JOB_NAME} build number ${BUILD_NUMBER} compare to previous build"

                slackSend color : '#d94c3a', channel: channel_name, message:slackMessage
            }
        } else {
            println "Artifacts produced in ${JOB_NAME} #(${BUILD_NUMBER}) is Matching with artifacts from previous builds artifacts"
        }
        return "${BUILD_VALIDATION}"
    }
}

def get_number_of_artifacts(def build_name, def build_number, def server_id, def type) {
    /*
     * get_number_of_artifacts function used to get number of artifacts from build info.
     * @param build_name: build name which added as build info in artifactory
     * @param build_number: build number
     * @param server_id: artifactory server id
     * @param type: artifact type ie. docker or any generic
     * @return: number of artifact (Integer)
     */
    def artifactory_url = get_rtf_url(server_id)
    def build_url = artifactory_url + "api/build/" + build_name + "/" + build_number
    def output = sh(script: "curl " + build_url, returnStdout: true)
    def json_str = readJSON text: output
    def no_of_artifacts = null
    if (type == "docker") {
        return json_str["buildInfo"].get("modules").size()
    } else {
        return json_str["buildInfo"].get("modules")[0]["artifacts"].size()
    }
}
