import com.cloudbees.groovy.cps.NonCPS
import hudson.model.*
import groovy.json.JsonOutput

/**
 *    This method is used to abort the given job
 *    job_name and build_number are passed as parameters
 */
@NonCPS
def kill_job(def job_name, int build_number) {
    Jenkins.instance.getItemByFullName(job_name)
            .getBuildByNumber(build_number)
            .finish(
            hudson.model.Result.ABORTED,
            new java.io.IOException("Aborting build")
            );
}

/**
 *    This method is used to trigger the downstream job inventory
 *    job type and version are passed as parameter
 */
def trigger_inventory_job(String release, String version) {
    if (release.split('\\.')[0].toInteger() >= 93) {
        build (
                job: 'inventory-develop-pipeline',
                propagate: false,
                wait: false,
                parameters: [
                    string(name: 'BUILD_TAG', value: version)
                ]
                )
    }
}

/**
 *    This method is used to trigger the process changeset
 *    from ns_pipeline library
 */
def trigger_process_changeset() {
    try {
        ns_pipeline.process_changeset()
    } catch(Exception ex) {
        console_log.error("Post build step failed!")
        console_log.error(ex.toString())
        console_log.error(ex.getMessage())
    }
}


/**
 *    This method is used to trigger manifest service
 *    job_name and build number are passed as parameter
 */
def trigger_manifest(String job_name, String build_number) {
    try{
        manifest_service.create_manifest_artifacts(job_name, build_number)
    } catch (Exception ex){
        console_log.error("Create manifest artifacts failed")
        console_log.error(ex.toString())
        console_log.error(ex.getMessage())
    }
}

/**
 *    This method is used to trigger the error handler job
 *    job_name, build number and release are passed as parameter
 *    slack_channel is passed as parameter which is used to notify the user
 *    about failure
 */
def trigger_error_handler(String job_name, String build_number, String release, String slack_channel) {
    def error_classifier_build = build (
            job: 'error-class-pipeline',
            propagate: false,
            wait: true,
            parameters: [
                string(name: 'JOB_NAME', value: job_name),
                string(name: 'BUILD_NUM', value: build_number),
                string(name: 'BUILD_TAG', value: release)
            ]
            )
    email.send_build_failure("build-failure-notifications@netskope.com", error_classifier_build)
    slack.send_with_jira(slack_channel, error_classifier_build)
}

/**
 *    This method is used to change the owner of the
 *    given directory. build_dir will be passed as parameter
 */
def change_permission(String build_dir) {
    dir("${build_dir}") {
        console_log.info("Changing permissions")
        sh "sudo -n chown -R $USER:$USER ${build_dir}"
    }
}

/**
 *    This method is used to configure the pip unsilence
 */
def unsilence_pip() {
    sh "sudo -H pip config unset global.silent --global"
}

/**
 *    This method is used to configure the pip silence
 */
def silence_pip() {
    sh "sudo -H pip config set global.silent 1 --global"
}

/**
 *    This method is used to invoke the command with retry
 *    cmd, num_time, wait_time and sleep_units are passed as parameter
 */
def invoke_command_with_retry(String cmd, int num_times = 2, int sleep_time = 10, String sleep_unit = "SECONDS") {
    console_log.info("Execute the command ${cmd} with ${num_times} retries")
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
            if(retryAttempt != num_times) {
                console_log.error("Waiting for ${sleep_time} ${sleep_unit} before retry")
                if(sleep_time > 0) {
                    sleep(time: sleep_time, unit: sleep_unit)
                }
            }
            throw ex
        }
    }
}


/**
 *    This method is used to get all the build parameters from the build using build number
 */
@NonCPS
def get_parameters(def currentJob) {

    def parameters_action = currentJob.getAction(hudson.model.ParametersAction.class)
    if (parameters_action) {
        return parameters_action.getAllParameters()
    }
    return null
}

def rebuild(def currentJob) {
    def parameters = get_parameters(currentJob)
    if (parameters == null) {
        build job: "${JOB_NAME}",  propagate: false, wait: false
        return
    }
    def build_params = []
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

/**
 *    This method is used to set the upstream to align with build number equivalent to down_stream jobs
 *    List of downstream jobs are passed as parameter
 */
def set_downstream_jobs_build_number(def down_stream_jobs){
    def down_stream_job_list = []
    def master_build_number = "${BUILD_NUMBER}".toInteger()
    def max_build_number = master_build_number
    def currentJob = Jenkins.instance.getItemByFullName("${JOB_NAME}")
    for (down_stream_job in down_stream_jobs) {
        def child_job = Jenkins.instance.getItemByFullName(down_stream_job)
        def child_build_number = child_job.getNextBuildNumber()
        down_stream_job_list.add(child_job)
        max_build_number = max_build_number > child_build_number ? max_build_number : child_build_number
    }

    if (master_build_number < max_build_number) {
        currentBuild.description = "Killing the master and downstream jobs's build number mismatched"
        currentJob.updateNextBuildNumber(max_build_number)
        rebuild(currentJob)
        currentBuild.doKill()
    } else {
        for (def down_stream_job in down_stream_job_list) {
            down_stream_job.updateNextBuildNumber(master_build_number)
        }
    }
}

/**
 *    This method is used to remove the excludable packages using the pattern array
 */
def remove_excludable_packages(String pkg_dir) {
    dir("${pkg_dir}") {
        def exclude_patterns = ["vpp*"]
        for (def pattern in exclude_patterns) {
            try {
                sh "rm -rf ${pattern}"
            } catch(Exception e){
                console_log.error("Didn't found pkgs as per " + pattern)
                console_log.error(e)
            }
        }
    }
}

/**
 *    This method is used to get the setuppy_sdist_option based on the env settings
 */
def get_setuppy_sdist_options() {
    if(env.QUIET_LOGGING && env.QUIET_LOGGING.toBoolean() == true) {
        return "\" --quiet \""
    }
    return ""
}

/**
 *    This method is used to build the adconnector package
 */
def build_adconnector_package(String build_dir, String version, String server_id, String repo_name, String branch_type, String release_version) {
    def version_found = get_client_adaptors_build_version(server_id, "${repo_name}", "${branch_type}", "${release_version}")
    def artifacts = get_client_adaptors_builds(server_id, "${repo_name}", "${branch_type}", "${version_found}")
    dir("${build_dir}/${repo_name}") {
        sh artifacts.join("\n")
    }
    sh "make -f compile/adconnector.mk VER=${version} INSTALLER_PATH=${build_dir}/${repo_name}"
}

/**
 *    This method is used to build the client installer package
 */
def build_clientinstaller_package(String build_dir, String version, String server_id, String repo_name, String branch_type, String release_version) {
    def version_found = get_client_adaptors_build_version(server_id, "${repo_name}", "${branch_type}", "${release_version}")
    def artifacts = get_client_adaptors_builds(server_id, "${repo_name}", "${branch_type}", "${version_found}")
    dir("${build_dir}/${repo_name}") {
        sh artifacts.join("\n")
    }
    sh "make -f compile/clientinstallers.mk VER=${version} INSTALLER_PATH=${build_dir}/${repo_name}"
}

/**
 *    This method is used to get the client adaptor details using the appropriate api
 */
def get_client_adaptors_builds(def server_id = "${RTF_SRC}", def repo_name, def branch_type, def version) {
    def server = Artifactory.server "${server_id}"
    def files = []
    def base_url = server.getUrl()+ "/api/storage/${repo_name}/${branch_type}/${version}/latest/Release"
    def response = httpRequest(
            consoleLogResponseBody: true,
            httpMode: 'GET',
            ignoreSslErrors: true,
            url: base_url
            )
    def children = new groovy.json.JsonSlurper().parseText(response.content)["children"]
    for (child in children) {
        if(child["folder"] == false){
            def file_name = child["uri"][1..-1].toString()
            files.add("curl -O ${server.getUrl()}/${repo_name}/${branch_type}/${version}/latest/Release/${file_name}")
        }
    }
    return files
}

/**
 *    This method is used to get the client adaptors build version using the specific api
 */
def get_client_adaptors_build_version(def server_id = "${RTF_SRC}", def repo_name, def branch_type, def release_version) {
    def server = Artifactory.server "${server_id}"
    def versions = []
    def version_found= null

    def base_url = server.getUrl()+ "/api/storage/${repo_name}/${branch_type}"
    def response = httpRequest(
            consoleLogResponseBody: true,
            httpMode: 'GET',
            ignoreSslErrors: true,
            url: base_url
            )
    def children = new groovy.json.JsonSlurper().parseText(response.content)["children"]
    for (child in children) {
        if(child["folder"] == true){
            version = child["uri"][1..-1]
            if(release_version.split('\\.')[0] == version.split('\\.')[0]) {
                versions.add(version)
            }
        }
    }
    if(versions.size() == 0){
        error("Couldn't find  ${repo_name} of version ${release_version}")
    }
    if(release_version in versions) {
        version_found = "${release_version}"
    }
    else {
        version_found = versions.sort()[-1]
        // this would fail, If we have more than 9 hotfix builds
    }
    return version_found
}

def get_version_using_ep_versioner(String artifactory_url, String ep_versioner_version) {
    def platform = sh(script : 'echo $(uname -s)_$(uname -m)' , returnStdout: true).split('\n')[0].toLowerCase()
    def versioner_dir = "ep-tools/ep-versioner/${ep_versioner_version}/${platform}"
    String  file_name = 'ep-versioner'
    def versioner_path = "${artifactory_url}/${versioner_dir}/${file_name}"
    sh "curl -o ./${file_name} ${versioner_path}"
    sh "chmod 755 ./${file_name}"
    def pkg_version = sh(script : "./${file_name} calc" , returnStdout: true)
    print("get_version_using_ep_versioner( ${artifactory_url}, ${ep_versioner_version}) ==> ${pkg_version}")
    return pkg_version
}

def update_build_description(def build_data) {
    String description = "<style>table {border-collapse: collapse;width: 100%;} th, td {padding: 8px;text-align: left;border-bottom: 1px solid #DDD;}tr:hover {background-color: #D6EEEE;}</style><table><tr style=\"color:green; background-color:white;\"><th>Parameters</th><th>Description</th></tr>"
    build_data.each {
        description += "<tr><td><b>" + it.key + "</b></td><td>" + it.value + "</td></tr>"
    }
    description += "</table>"
    currentBuild.setDescription(description)
}

/**
 *    This method is used to check the infrastructure is ready to proceed with build
 *    num_times, wait_time and sleep_units are passed as parameter
 */
def infra_ready_check(int num_times = 5, int sleep_time = 10, String sleep_unit = "SECONDS") {
    def github_status_url = "https://www.githubstatus.com/api/v2/summary.json"
    git_health_check(github_status_url, num_times, sleep_time, sleep_unit)
    def artifactory = Artifactory.server params.RTF_SRC
    def artifactory_url = artifactory.getUrl() + "/api/system/ping"
    rtf_health_check(artifactory_url, num_times, sleep_time, sleep_unit)
}

/**
 *    This method is used to check the artifactory server reachability
 *    artifactory_url, num_time, wait_time and sleep_units are passed as parameter
 */
def rtf_health_check(String req_url, int num_times, int sleep_time, String sleep_unit) {
    def retryAttempt = 0
    retry(num_times) {
        retryAttempt = retryAttempt + 1
        try {
            def response = httpRequest(
                    url: req_url,
                    httpMode: 'GET',
                    validResponseCodes: '200'
                    )
            def status = response.status
            print("status :" + status)
            def content = response.content;
            print("Content:" + content)
            if(status != 200 || content != 'OK') {
                throw new Exception("Artifactory server ${req_url} is not accessible.")
            }
        } catch(Exception ex) {
            if(retryAttempt != num_times) {
                console_log.error("Waiting for ${sleep_time} ${sleep_unit} before retry")
                sleep(time: sleep_time, unit: sleep_unit)
                sh 'false'
            } else {
                kill_job("${JOB_NAME}", "${BUILD_NUMBER}".toInteger())
            }
        }
    }
}

/**
 *    This method is used to check the git server reachability
 *    githubstatus_url, num_time, wait_time and sleep_units are passed as parameter
 */
def git_health_check(String req_url, int num_times, int sleep_time, String sleep_unit) {
    def retryAttempt = 0
    retry(num_times) {
        retryAttempt = retryAttempt + 1
        try {
            def response = httpRequest(
                    url: req_url,
                    httpMode: 'GET',
                    validResponseCodes: '200'
                    )

            def status = response.status
            print("status :" + status)
            def content = new groovy.json.JsonSlurper().parseText(response.content)
            if(status != 200) {
                throw new Exception("Git server is not operational. status code ${status}")
            }
            if(content != null && content["components"] != null) {
                for(def component in content["components"]) {
                    if(component["name"] == "Git Operations") {
                        if(component["status"] != "operational") {
                            throw new Exception("Git server is not operational. server response ${component}")
                        }
                        break;
                    }
                }
            }
        } catch(Exception ex) {
            if(retryAttempt != num_times) {
                console_log.error("Waiting for ${sleep_time} ${sleep_unit} before retry")
                sleep(time: sleep_time, unit: sleep_unit)
                sh 'false'
            } else {
                kill_job("${JOB_NAME}", "${BUILD_NUMBER}".toInteger())
            }
        }
    }
}


def get_drone_build_by_tag(String drone_url, String owner, String repo_name, String tag_name) {
    def response = null
    try {
        def req_url = "${drone_url}/api/repos/${owner}/${repo_name}/builds"
        withCredentials([
            string(credentialsId: 'DRONE-TOKEN', variable: 'TOKEN')
        ]) {
            response = httpRequest(
                    url: req_url,
                    customHeaders: [
                        [maskValue: true, name: 'Authorization', value: 'Bearer ' + TOKEN]
                    ],
                    httpMode: 'GET',
                    validResponseCodes: '200'
                    )
        }
    } catch(Exception ex) {
        error("Failed to fetch the builds from drone ${drone_url}")
    }
    def drone_builds = readJSON text: response.content
    for(build_data in drone_builds) {
        if(build_data["event"] == "tag" && build_data["ref"] == "refs/tags/${tag_name}") {
            return [
                build_data["number"],
                build_data["status"]
            ]
        }
    }
    println("DEBUG:: Available drone builds are "+ JsonOutput.toJson(drone_builds))
    error("Couldn't find the drone build from the tag [${tag_name}] in drone [${drone_url}] for repository ${repo_name}")
}

def validate_drone_builds(String drone_url, String owner, List repositories, String tag_name, String slack_channel) {
    String message = ""
    String failed_message = ""
    for (repository in repositories) {
        (build_no, status) = get_drone_build_by_tag(drone_url, owner, repository, tag_name)
        String build_url = "${drone_url}/${owner}/${repository}/${build_no}"
        String console_msg = "Drone build status for repository ${repository} is ${status}.\n The Build link is ${build_url} \n"
        message = message + console_msg
        if(status != "success") {
            failed_message = failed_message + console_msg
            slack.send(slack_channel, failed_message)
        }
        print(message)
    }
}
