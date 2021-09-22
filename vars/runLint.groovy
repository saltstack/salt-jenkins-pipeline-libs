def call(Map options) {

    def lint_report_issues = []

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
    def String jenkins_slave_label = options.get('jenkins_slave_label', 'lint')
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
        try {
            // Checkout the repo
            stage('Clone') {
                cleanWs notFailBuild: true
                checkout scm
            }

            // Setup the kitchen required bundle
            stage('Setup') {


                if (env.CHANGE_ID) {
                    // Only lint changes on PR builds
                    sh '''
                    # Need -M to detect renames otherwise they are reported as Delete and Add, need -C to detect copies, -C includes -M
                    # -M is on by default in git 2.9+
                    # CHANGE_TARGET only exists on PR builds
                    git diff --name-status -l99999 -C "origin/${CHANGE_TARGET}" > file-list-status.log
                    # the -l increase the search limit, lets use awk so we do not need to repeat the search above.
                    gawk 'BEGIN {FS="\\t"} {if ($1 != "D") {print $NF}}' file-list-status.log > file-list-changed.log
                    gawk 'BEGIN {FS="\\t"} {if ($1 == "D") {print $NF}}' file-list-status.log > file-list-deleted.log
                    (git diff --name-status -l99999 -C "origin/${CHANGE_TARGET}" "origin/${BRANCH_NAME}";echo "---";git diff --name-status -l99999 -C "origin/${BRANCH_NAME}";printenv|grep -E '=[0-9a-z]{40,}+$|COMMIT=|BRANCH') > file-list-experiment.log
                    '''
                    archiveArtifacts(
                        artifacts: 'file-list-status.log,file-list-changed.log,file-list-deleted.log,file-list-experiment.log',
                        allowEmptyArchive: true
                    )
                }

                sh '''
                eval "$(pyenv init -)"
                pyenv --version
                pyenv install --skip-existing 3.8.11
                pyenv shell 3.8.11
                python --version
                pip3 install -U nox
                nox --version
                # Create the required virtualenvs in serial
                nox --install-only -e lint-salt
                nox --install-only -e lint-tests
                '''
            }

            if (env.CHANGE_ID) {
                // Only lint changes on PR builds
                stage('Lint Changes') {
                    try {
                        parallel(
                            lintSalt: {
                                stage('Lint Salt Changes') {
                                    if (readFile('file-list-changed.log') =~ /(?i)(^|\n)(salt\/.*\.py|setup\.py)\n/) {
                                        sh '''
                                        eval "$(pyenv init - --no-rehash)"
                                        pyenv shell 3.8.11
                                        EC=254
                                        export PYLINT_REPORT=pylint-report-salt-chg.log
                                        grep -Ei '^salt/.*\\.py$|^setup\\.py$' file-list-changed.log | xargs -r '--delimiter=\\n' nox -e lint-salt --
                                        EC=$?
                                        exit $EC
                                        '''
                                    } else {
                                        // Always lint something so reporting doesn't fail
                                        sh '''
                                        eval "$(pyenv init - --no-rehash)"
                                        pyenv shell 3.8.11
                                        EC=254
                                        export PYLINT_REPORT=pylint-report-salt-chg.log
                                        nox -e lint-salt -- salt/ext/__init__.py
                                        EC=$?
                                        exit $EC
                                        '''
                                    }
                                }
                            },
                            lintTests: {
                                stage('Lint Test Changes') {
                                    if (readFile('file-list-changed.log') =~ /(?i)(^|\n)tests\/.*\.py\n/) {
                                        sh '''
                                        eval "$(pyenv init - --no-rehash)"
                                        pyenv shell 3.8.11
                                        EC=254
                                        export PYLINT_REPORT=pylint-report-tests-chg.log
                                        grep -Ei '^tests/.*\\.py$' file-list-changed.log | xargs -r '--delimiter=\\n' nox -e lint-tests --
                                        EC=$?
                                        exit $EC
                                        '''
                                    }
                                }
                            }
                        )
                    } finally {
                        def changed_logs_pattern = 'pylint-report-*-chg.log'
                        archiveArtifacts(
                            artifacts: changed_logs_pattern,
                            allowEmptyArchive: true
                        )
                        lint_report_issues.add(
                            scanForIssues(
                                tool: pyLint(pattern: changed_logs_pattern, reportEncoding: 'UTF-8')
                            )
                        )
                    }
                }
            }

            stage('Lint Full') {
                // Perform a full linit if change only lint passed
                try {
                    parallel(
                        lintSaltFull: {
                            stage('Lint Salt Full') {
                                sh '''
                                eval "$(pyenv init - --no-rehash)"
                                pyenv shell 3.8.11
                                EC=254
                                export PYLINT_REPORT=pylint-report-salt-full.log
                                nox -e lint-salt
                                EC=$?
                                exit $EC
                                '''
                            }
                        },
                        lintTestsFull: {
                            stage('Lint Tests Full') {
                                sh '''
                                eval "$(pyenv init - --no-rehash)"
                                pyenv shell 3.8.11
                                EC=254
                                export PYLINT_REPORT=pylint-report-tests-full.log
                                nox -e lint-tests
                                EC=$?
                                exit $EC
                                '''
                            }
                        }
                    )
                } finally {
                    def full_logs_pattern = 'pylint-report-*-full.log'
                    archiveArtifacts(
                        artifacts: full_logs_pattern,
                        allowEmptyArchive: true
                    )
                    lint_report_issues.add(
                        scanForIssues(
                            tool: pyLint(pattern: full_logs_pattern, reportEncoding: 'UTF-8')
                        )
                    )
                }
            }
        } finally {
            def reference_job_name
            if (env.CHANGE_ID) {
                reference_job_name = "env.CHANGE_TARGET"
            } else {
                reference_job_name = "env.BRANCH_NAME"
            }
            publishIssues(
                referenceJobName: "pr-lint/${reference_job_name}",
                qualityGates: [
                    [threshold: 1, type: 'TOTAL', unstable: false]
                ],
                issues: lint_report_issues
            )
        }
    }
}
