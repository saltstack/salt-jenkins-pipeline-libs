def call(Map options) {

    if (env.CHANGE_ID) {
        properties([
            buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '3', daysToKeepStr: '', numToKeepStr: '5')),
        ])
    } else {
        properties([
            [$class: 'BuildDiscarderProperty', strategy: [$class: 'EnhancedOldBuildDiscarder', artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '30', numToKeepStr: '30',discardOnlyOnSuccess: true, holdMaxBuilds: true]],
        ])
    }

    def env = options.get('env')
    def Integer concurrent_builds = options.get('concurrent_builds', 1)
    def Integer testrun_timeout = options.get('testrun_timeout', 2)
    def String jenkins_slave_label = options.get('jenkins_slave_label', 'docs')
    def String notify_slack_channel = options.get('notify_slack_channel', '')

    if ( notify_slack_channel == '' ) {
        if (env.CHANGE_ID) {
            // This is a PR
            notify_slack_channel = '#jenkins-prod-pr'
        } else {
            // This is not a PR
            notify_slack_channel = '#jenkins-prod'
        }
    }

    // Enforce build concurrency
    enforceBuildConcurrency(options)

    wrappedNode(jenkins_slave_label, testrun_timeout, notify_slack_channel) {

        // Checkout the repo
        stage('Clone') {
            cleanWs notFailBuild: true
            checkout scm
        }

        // Setup the kitchen required bundle
        stage('Setup') {
            sh '''
            eval "$(pyenv init -)"
            pyenv --version
            pyenv install --skip-existing 3.8.12
            pyenv shell 3.8.12
            python --version
            pip install -U nox
            nox --version
            '''
        }

        stage('Build HTML Docs') {
            sh '''
            eval "$(pyenv init -)"
            pyenv shell 3.8.12
            nox -e 'docs-html(compress=True, clean=True)'
            '''
            archiveArtifacts artifacts: 'doc/html-archive.tar.*'
        }

        stage('Build Man Pages') {
            sh '''
            eval "$(pyenv init -)"
            pyenv shell 3.8.12
            nox -e 'docs-man(compress=True, update=False, clean=True)'
            '''
            archiveArtifacts artifacts: 'doc/man-archive.tar.*'
        }
    }
}
