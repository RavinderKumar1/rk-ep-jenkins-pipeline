@Library('ns_pipeline_library')_
def service_sha = null
def dataplane_sha = null
def dataplane_pkg_version = null
def webui_sha = null
def webui_branch = null
def webui_url = null
def dataplane_url = null
def dataplane_branch = null
def ns_pipeline
def slack_channel = "#develop-builds"
def drone_build_repos = [
    "cci",
    "irm",
    "tokenservice",
    "discoverygatekeeper",
    "conductorservice"
]

pipeline {
    agent {
        label "${AGENT_LABEL}"
    }
    environment {
        BRANCH_TYPE = "develop"
        NS_BUILD_DIR = "${env.WORKSPACE}"
        VERSION = "1.develop-${RELEASE}.${BUILD_NUMBER}"
        START_TIME = new Date().getTime();
        GIT_REMOTE = "https://github.com/netSkope/service.git"
    }
    options {
        timeout(time: params.MAX_DURATION ? params.MAX_DURATION : 12, unit: 'HOURS')
        buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '2', daysToKeepStr: '30', numToKeepStr: '30');
        timestamps()
    }
    stages {
        stage('Infrastructure Readiness check') {
            steps {
                script {
                    println('skipping build_util step for now')
                    //this will check whether github and artifactory are accessible
                    //build_util.infra_ready_check()
                }
            }
        }
        stage('Change file permissions to user for the repo') {
            steps {
                dir("${NS_BUILD_DIR}") {
                    println "Changing permissions"
                    sh "sudo -n chown -R $USER:$USER ${NS_BUILD_DIR}"
                }
            }
        }
        stage("Create tags") {
            steps {
                script {
                    def tokenservice_commit_sha = ns_pipeline.get_last_commit_sha(
                            "tokenservice",
                            "${BRANCH}"
                            )
                    ns_pipeline.create_tag(
                            "${VERSION}",
                            "tokenservice",
                            tokenservice_commit_sha
                            )
                    def discoverygatekeeper_commit_sha = ns_pipeline.get_last_commit_sha(
                            "discoverygatekeeper",
                            "master"
                            )
                    ns_pipeline.create_tag(
                            "${VERSION}",
                            "discoverygatekeeper",
                            discoverygatekeeper_commit_sha
                            )
                    def conductorservice_commit_sha = ns_pipeline.get_last_commit_sha(
                            "conductorservice",
                            "develop"
                            )
                    ns_pipeline.create_tag(
                            "${VERSION}",
                            "conductorservice",
                            conductorservice_commit_sha
                            )
                    def irm_commit_sha = ns_pipeline.get_last_commit_sha(
                            "irm",
                            "develop"
                            )
                    ns_pipeline.create_tag(
                            "${VERSION}",
                            "irm",
                            irm_commit_sha
                            )
                    def cci_commit_sha = ns_pipeline.get_last_commit_sha(
                            "cci",
                            "develop"
                            )
                    ns_pipeline.create_tag(
                            "${VERSION}",
                            "cci",
                            cci_commit_sha
                            )
                    def suricata_commit_sha = ns_pipeline.get_last_commit_sha (
                            "suricata-inline",
                            "develop"
                            )
                    ns_pipeline.create_tag (
                            "${VERSION}",
                            "suricata-inline",
                            suricata_commit_sha
                            )
                }
            }
        }
        stage('Checkout') {
            steps {
                script {
                    stage('service') {
                        script {
                            dir("${NS_BUILD_DIR}") {
                                ns_pipeline.git_check_out("${GIT_REMOTE}", "${BRANCH}", true, false, 120, false, "github-https")
                            }
                        }
                    }

                    def checkoutstg = [:]
                    checkoutstg['dataplane'] = {
                        script {
                            dataplane_url = ns_pipeline.get_submodule("dp", "url")
                            dataplane_branch = ns_pipeline.get_submodule("dp", "branch")
                            dataplane_sha = ns_pipeline.get_sha_from_manifest("dataplane", dataplane_branch)
                            dataplane_pkg_version = ns_pipeline.get_version_from_manifest("dataplane", dataplane_branch)
                            dir("$NS_BUILD_DIR/dataplane/src") {
                                ns_pipeline.git_check_out("${dataplane_url}", "${dataplane_sha}", true, false, 120, false, "github-https", true, true)
                            }
                        }
                    }

                    checkoutstg['webui'] = {
                        script {
                            webui_branch = ns_pipeline.get_submodule("components/webui", "branch")
                            webui_url = ns_pipeline.get_submodule("components/webui", "url")
                            dir("$NS_BUILD_DIR/components/webui") {
                                ns_pipeline.git_check_out("${webui_url}", "${webui_branch}", true, false, 120, false, "github-https")
                            }
                        }
                    }

                    parallel(checkoutstg)

                    service_sha = ns_pipeline.get_commit_id_by_remote(
                            "${JOB_NAME}".toString(),
                            "${BUILD_NUMBER}".toString(),
                            "${GIT_REMOTE}"
                            )
                    webui_sha = ns_pipeline.get_commit_id_by_remote(
                            "${JOB_NAME}".toString(),
                            "${BUILD_NUMBER}".toString(),
                            "${webui_url}"
                            )
                    cleanWs()
                }
            }
        }
        stage('Update Build description') {
            steps {
                script {
                    def build_data = [
                        "SERVICE_COMMIT_SHA" : "${service_sha}",
                        "DATAPLANE_COMMIT_SHA" : "${dataplane_sha}",
                        "WEBUI_COMMIT_SHA" : "${webui_sha}",
                        "DATAPLANE_PKG_VERSION" : "${dataplane_pkg_version}",

                    ]
                    //build_util.update_build_description(build_data)
                }
            }
        }
        stage("Align Builds Numbers") {
            steps {
                script {
                    println('skipping build_util step for now')
                    //build_util.set_downstream_jobs_build_number(
                    //        [
                    //            "${UB16_JOB}",
                    //            "${UB20_JOB}"
                    //        ]
                    //        )
                }
            }
        }
        stage('Trigger downstream Jobs') {
            parallel {
                stage('Build Ubuntu 20') {
                    steps {
                        build job: "${UB20_JOB}", parameters: [
                            string(name: 'SERVICE_COMMIT_SHA', value: "${service_sha}"),
                            string(name: 'DATAPLANE_COMMIT_SHA', value: "${dataplane_sha}"),
                            string(name: 'DP_BRANCH', value: "${dataplane_branch}"),
                            string(name: 'DATAPLANE_PKG_VERSION', value: "${dataplane_pkg_version}"),
                            string(name: 'WEBUI_COMMIT_SHA', value: "${webui_sha}"),
                            string(name: 'BUILD_NUM', value: "${BUILD_NUMBER}"),
                            string(name: 'RELEASE', value: "${RELEASE}"),
                            string(name: 'ARTIFACTORY_REPO', value: "${ARTIFACTORY_REPO}"),
                            string(name: 'SKIP_COMPONENTS', value: "${SKIP_COMPONENTS}"),
                            booleanParam(name: 'BUILD_CLIENT_AD', value: "${BUILD_CLIENT_AD}".toBoolean())
                        ]
                    }
                }
                stage('Build Ubuntu 16') {
                    steps {
                        build job: "${UB16_JOB}", parameters: [
                            string(name: 'SERVICE_COMMIT_SHA', value: "${service_sha}"),
                            string(name: 'DATAPLANE_COMMIT_SHA', value: "${dataplane_sha}"),
                            string(name: 'DP_BRANCH', value: "${dataplane_branch}"),
                            string(name: 'DATAPLANE_PKG_VERSION', value: "${dataplane_pkg_version}"),
                            string(name: 'WEBUI_COMMIT_SHA', value: "${webui_sha}"),
                            string(name: 'BUILD_NUM', value: "${BUILD_NUMBER}"),
                            string(name: 'RELEASE', value: "${RELEASE}"),
                            string(name: 'ARTIFACTORY_REPO', value: "${ARTIFACTORY_REPO}"),
                            string(name: 'SKIP_COMPONENTS', value: "${SKIP_COMPONENTS}"),
                            booleanParam(name: 'BUILD_CLIENT_AD', value: "${BUILD_CLIENT_AD}".toBoolean())
                        ]
                    }
                }
            }
        }
        stage("Create tags for dlp-kube-service") {
            steps {
                script{
                    def dlp_kube_service_commit_sha = ns_pipeline.get_last_commit_sha(
                            "dlp-kube-service",
                            "develop"
                            )
                    ns_pipeline.create_tag(
                            "${VERSION}",
                            "dlp-kube-service",
                            dlp_kube_service_commit_sha
                            )
                }
            }
        }
        stage('Trigger stork downstream Job') {
            steps {
                script {
                    def downstream_jobs = [
                        "${JOB_NAME}".toString().replace("service", "stork")
                    ]
                    for (job_name in downstream_jobs) {
                        def job_params = []
                        if (webui_sha) {
                            job_params.add(string(name: 'WEBUI_COMMIT_SHA', value: "${webui_sha}"))
                        }
                        if (dataplane_sha) {
                            job_params.add(string(name: 'DATAPLANE_COMMIT_SHA', value: "${dataplane_sha}"))
                        }
                        if (service_sha) {
                            job_params.add(string(name: 'SERVICE_COMMIT_SHA', value: "${service_sha}"))
                        }
                        job_params.add(string(name: 'RELEASE', value: "${RELEASE}"))
                        job_params.add(string(name: 'STORK_BASE_IMAGE_SUFFIX', value: "${STORK_BASE_IMAGE_SUFFIX}"))
                        build(
                                job: job_name,
                                propagate: false,
                                wait: false,
                                parameters: job_params
                                )
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                if ("${RELEASE}".split('\\.')[0].toInteger() >= 93) {
                    build(
                            job: 'inventory-develop-pipeline',
                            propagate: false,
                            wait: false,
                            parameters: [
                                string(name: 'BUILD_TAG', value: String.valueOf("${VERSION}"))
                            ]
                            )
                }
                ns_pipeline.collect_metrics(env.START_TIME as Long, currentBuild.currentResult)
                // https://github.com/netSkope/ep-arwen/blob/develop/README.md
                ns_pipeline.run_eparwen("all post-build")
                try {
                    println('skipping build_util step for now')
                    //build_util.validate_drone_builds("https://ci-drone-int.netskope.io", "netSkope", drone_build_repos, "${VERSION}", slack_channel)
                } catch(Exception e) {
                    slack.send(slack_channel, "Failed to validate the drone builds")
                }
                try {
                    ns_pipeline.process_changeset()
                } catch (Exception ex) {
                    def jira_list = ns_pipeline.get_jira_tickets()
                    println(ex.toString())
                    println(ex.getMessage())
                    slack.send(slack_channel, "Failed to update jira(s): ${jira_list}")
                    error "Failed to update jira(s): ${jira_list}"
                }
            }
        }
        failure {
            script {
                def error_classifier_build = build(
                        job: 'error-class-pipeline',
                        propagate: false,
                        wait: true,
                        parameters: [
                            string(name: 'JOB_NAME', value: "${JOB_NAME}"),
                            string(name: 'BUILD_NUM', value: "${BUILD_NUMBER}"),
                            string(name: 'BUILD_TAG', value: "${RELEASE}"),
                            string(name: 'JOBS', value: "service-develop-pipeline,ub16-service-develop-pipeline,ub20-service-develop-pipeline"),
                            string(name: 'JOB_BUILD_URL', value: "${BUILD_URL}")
                        ]
                        )
                ns_pipeline.send_build_failure_msg("build-failure-notifications@netskope.com", error_classifier_build)
                ns_pipeline.send_slack_msg_with_jira(slack_channel, error_classifier_build)
                ns_pipeline.remove_pip_cache_lock_file()
                ns_pipeline.remove_packages(".", true)
            }
        }
        aborted {
            script {
                ns_pipeline.send_slack_msg("develop-builds")
            }
        }
        unstable {
            script {
                ns_pipeline.send_slack_msg("develop-builds")
                ns_pipeline.send_failure_msg()
                ns_pipeline.remove_pip_cache_lock_file()
                ns_pipeline.remove_packages(".", true)
            }
        }
    }
}
