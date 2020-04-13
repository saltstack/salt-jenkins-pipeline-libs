def call(Map options) {

    if (env.CHANGE_ID) {
        properties([
            buildDiscarder(
                logRotator(
                    artifactDaysToKeepStr: '',
                    artifactNumToKeepStr: '3',
                    daysToKeepStr: '',
                    numToKeepStr: '5'
                )
            ),
        ])
    } else {
        properties(
            [
                [
                    $class: 'BuildDiscarderProperty',
                    strategy: [
                        $class: 'EnhancedOldBuildDiscarder',
                        artifactDaysToKeepStr: '',
                        artifactNumToKeepStr: '',
                        daysToKeepStr: '30',
                        numToKeepStr: '30',
                        discardOnlyOnSuccess: true,
                        holdMaxBuilds: true
                    ]
                ],
            ]
        )
    }

    def env = options.get('env')
    def Integer concurrent_builds = options.get('concurrent_builds', 1)
    def Integer testrun_timeout = options.get('testrun_timeout', 3)
    def String jenkins_slave_label = options.get('jenkins_slave_label', 'pre-commit')
    def String notify_slack_channel = options.get('notify_slack_channel', '')

    if ( notify_slack_channel == '' ) {
        if (env.CHANGE_ID) {
            // This is a PR
            notify_slack_channel = '#jenkins-prod-pr'
        } else {
            // This is a branch build
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
            pyenv install --skip-existing 3.7.6
            pyenv shell 3.7.6
            python --version
            pip3 install -U nox-py2==2019.6.25 pre-commit
            nox --version
            # Install pre-commit
            pre-commit install --install-hooks
            '''
        }

        try {
            sh '''
            if [ -f ~/.pypirc ]; then
                mv ~/.pypirc ~/.pypirc.bak
            fi
            '''
            if (env.CHANGE_ID) {
                stage('Pre-Commit Changes') {
                    sh '''
                    set -e
                    eval "$(pyenv init - --no-rehash)"
                    pyenv shell 3.7.6
                    # Lint checks have it's own Jenkins job
                    export SKIP=lint-salt,lint-tests
                    pre-commit run --color always --show-diff-on-failure --from-ref "origin/${CHANGE_TARGET}" --to-ref "origin/${BRANCH_NAME}"
                    '''
                }
            } else {
                stage('Pre-Commit') {
                    sh '''
                    set -e
                    eval "$(pyenv init - --no-rehash)"
                    pyenv shell 3.7.6
                    # Lint checks have it's own Jenkins job
                    export SKIP=lint-salt,lint-tests
                    pre-commit run --color always --show-diff-on-failure -a
                    '''
                }
            }
        } finally {
            sh '''
            if [ -f ~/.pypirc.bak ]; then
                mv ~/.pypirc.bak ~/.pypirc
            fi
            '''
        }
    }
}
