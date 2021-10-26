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
    def String pre_commit_skips = options.get('pre_commit_skips', '')
    // Lint checks have it's own Jenkins job
    def _pre_commit_skips = ['lint-salt', 'lint-tests']

    if ( notify_slack_channel == '' ) {
        if (env.CHANGE_ID) {
            // This is a PR
            notify_slack_channel = '#salt-jenkins-pr'
        } else {
            // This is a branch build
            notify_slack_channel = '#salt-jenkins'

        }
    }

    if ( pre_commit_skips != '' ) {
        pre_commit_skips.split(",").each { skip ->
            _pre_commit_skips << skip.trim()
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
            pip3 install -U nox pre-commit
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
            if [ -f ~/.config/pip/pip.conf ]; then
                mv ~/.config/pip/pip.conf ~/.config/pip/pip.conf.bak
            fi
            '''
            withEnv(["SKIP=${_pre_commit_skips.join(',')}"]) {
                if (env.CHANGE_ID) {
                    stage('Pre-Commit Changes') {
                        sh '''
                        set -e
                        eval "$(pyenv init - --no-rehash)"
                        pyenv shell 3.8.12
                        pre-commit run --color always --show-diff-on-failure --from-ref "origin/${CHANGE_TARGET}" --to-ref "origin/${BRANCH_NAME}"
                        '''
                    }
                } else {
                    stage('Pre-Commit') {
                        sh '''
                        set -e
                        eval "$(pyenv init - --no-rehash)"
                        pyenv shell 3.8.12
                        pre-commit run --color always --show-diff-on-failure -a
                        '''
                    }
                }
            }
        } finally {
            sh '''
            if [ -f ~/.pypirc.bak ]; then
                mv ~/.pypirc.bak ~/.pypirc
            fi
            if [ -f ~/.config/pip/pip.conf.bak ]; then
                mv ~/.config/pip/pip.conf.bak ~/.config/pip/pip.conf
            fi
            '''
        }
    }
}
