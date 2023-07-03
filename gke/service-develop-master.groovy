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
        stage('Get Repo map') {
            steps {
                script {
                    ns_pipeline.get_repo_map()
                }
            }
        }
    }
}
