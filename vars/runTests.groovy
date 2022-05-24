def call(Map options) {

    def Boolean run_full = true

    if (env.CHANGE_ID) {
        properties([
            buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '3', daysToKeepStr: '', numToKeepStr: '5')),
            parameters([
                booleanParam(defaultValue: false, description: 'Run full test suite, including slow tests', name: 'runFull'),
                booleanParam(defaultValue: false, description: 'Re-run failed tests at the end of the test run', name: 'reRunFailedTests')
            ])
        ])
    } else {
        properties([
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
            parameters([
                booleanParam(defaultValue: true, description: 'Run full test suite, including slow tests', name: 'runFull'),
                booleanParam(defaultValue: false, description: 'Re-run failed tests at the end of the test run', name: 'reRunFailedTests')
            ])
        ])
    }
    run_full = params.runFull
    rerun_failed_tests = params.reRunFailedTests

    def env = options.get('env')
    def String distro_name = options.get('distro_name')
    def String distro_version = options.get('distro_version')
    def String distro_arch = options.get('distro_arch')
    def String python_version = options.get('python_version')
    def String nox_env_name = options.get('nox_env_name')
    def String nox_passthrough_opts = options.get('nox_passthrough_opts')
    def Integer testrun_timeout = options.get('testrun_timeout', 6)
    def Integer inactivity_timeout_minutes = options.get('inactivity_timeout_minutes', 30)
    def Boolean use_spot_instances = options.get('use_spot_instances', false)
    def String rbenv_version = options.get('rbenv_version', '2.6.3')
    def String jenkins_slave_label = options.get('jenkins_slave_label', 'kitchen-slave')
    def String notify_slack_channel = options.get('notify_slack_channel', '')
    def String kitchen_verifier_file = options.get('kitchen_verifier_file', '/var/jenkins/workspace/nox-verifier.yml')
    def String kitchen_platforms_file = options.get('kitchen_platforms_file', '/var/jenkins/workspace/platforms.yml')
    def String[] extra_codecov_flags = options.get('extra_codecov_flags', [])
    def String ami_image_id = options.get('ami_image_id', '')
    def Boolean upload_test_coverage = options.get('upload_test_coverage', true)
    def Boolean upload_split_test_coverage = options.get('upload_split_test_coverage', true)
    def Integer concurrent_builds = options.get('concurrent_builds', 1)
    def String test_suite_name = options.get('test_suite_name', 'full')
    def Boolean force_run_full = options.get('force_run_full', false)
    def Boolean disable_from_filenames = options.get('disable_from_filenames', false)
    def String macos_python_version = options.get('macos_python_version', '3.7')
    def String vm_hostname = computeMachineHostname(
        env: env,
        distro_name: distro_name,
        distro_version: distro_version,
        distro_arch: distro_arch,
        python_version: python_version,
        nox_env_name: nox_env_name,
        extra_parts: extra_codecov_flags,
    )

    def String kitchen_driver_file

    if ( distro_name.startsWith('windows') ) {
        kitchen_driver_file = options.get('kitchen_driver_file', '/var/jenkins/workspace/driver-win.yml')
    } else {
        kitchen_driver_file = options.get('kitchen_driver_file', '/var/jenkins/workspace/driver.yml')
    }

    if ( notify_slack_channel == '' ) {
        if (env.CHANGE_ID) {
            // This is a PR
            notify_slack_channel = '#salt-jenkins-pr'
        } else {
            // This is a branch build
            notify_slack_channel = '#salt-jenkins'
        }
    }

    if ( upload_test_coverage ) {
        if ( env.UPLOAD_TEST_COVERAGE != "true" ) {
            upload_test_coverage = false
            echo "Code coverage uploading globaly disabled by UPLOAD_TEST_COVERAGE=${env.UPLOAD_TEST_COVERAGE} env variable set in jenkins global config"
        }
    } else {
        echo "Code coverage uploading disabled on the pipeline settings"
    }

    if ( force_run_full || env.FORCE_RUN_FULL == "true" ) {
        run_full = true
    }

    if ( run_full ) {
        // If run_full is true, pass the --run-slow flag
        nox_passthrough_opts = "${nox_passthrough_opts} --run-slow"
    }

    def Boolean macos_build = false
    if ( distro_name.startsWith('macos') ) {
        macos_build = true
    }

    def String use_spot_instances_overridden
    /* if ( env.JENKINS_URL.matches(".*private-jenkins.*") ) {
        use_spot_instances_overridden = " (Overridden. Previously set to: ${use_spot_instances})"
        use_spot_instances = false
    } else {
        use_spot_instances_overridden = ""
    }
    */

    // Force the non usage of SPOT instances
    if ( use_spot_instances == true ) {
        use_spot_instances_overridden = " (Overridden. Previously set to: ${use_spot_instances})"
        use_spot_instances = false
    } else {
        use_spot_instances_overridden = " (Not Overridden)"
    }

    // Define a global pipeline timeout. This is the test run timeout with one(1) additional
    // hour to allow for artifacts to be downloaded, if possible.
    def global_timeout = testrun_timeout + 1

    // Enforce build concurrency
    enforceBuildConcurrency(options)

    echo """\
    Distro: ${distro_name}
    Distro Version: ${distro_version}
    Distro Arch: ${distro_arch}
    Python Version: ${python_version}
    Nox Env Name: ${nox_env_name}
    Nox Passthrough Opts: ${nox_passthrough_opts}
    Test run timeout: ${testrun_timeout} Hours
    Global Timeout: ${global_timeout} Hours
    Full Testsuite Run: ${run_full}
    Use SPOT instances: ${use_spot_instances}${use_spot_instances_overridden}
    RBEnv Version: ${rbenv_version}
    Jenkins Slave Label: ${jenkins_slave_label}
    Notify Slack Channel: ${notify_slack_channel}
    Kitchen Driver File: ${kitchen_driver_file}
    Kitchen Verifier File: ${kitchen_verifier_file}
    Kitchen Platforms File: ${kitchen_platforms_file}
    Computed Machine Hostname: ${vm_hostname}
    """.stripIndent()

    def environ = [
        "SALT_KITCHEN_PLATFORMS=${kitchen_platforms_file}",
        "SALT_KITCHEN_VERIFIER=${kitchen_verifier_file}",
        "SALT_KITCHEN_DRIVER=${kitchen_driver_file}",
        "NOX_ENV_NAME=${nox_env_name.toLowerCase()}",
        'NOX_ENABLE_FROM_FILENAMES=true',
        "NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts}",
        "CODECOV_FLAGS=${distro_name}${distro_version},${distro_arch},${python_version},${nox_env_name.toLowerCase().split('-').join(',')}",
        "RBENV_VERSION=${rbenv_version}",
        "TEST_SUITE=${python_version}",
        "TEST_PLATFORM=${distro_name}-${distro_version}-${distro_arch}",
        "FORCE_FULL=true",
        "TEST_VM_HOSTNAME=${vm_hostname}"
    ]

    if ( ami_image_id != '' ) {
        echo """\
        Amazon AMI: ${ami_image_id}
        """.stripIndent()
        environ << "AMI_IMAGE_ID=${ami_image_id}"
    }

    def String clone_stage_name
    def String setup_stage_name
    def String create_stage_name
    def String vagrant_box_details_stage_name
    def String converge_stage_name
    def String run_tests_stage_name
    def String download_stage_name
    def String cleanup_stage_name
    def String upload_stage_name

    if ( test_suite_name == 'full' ) {
        test_suite_name_slug = test_suite_name
        clone_stage_name = "Clone"
        setup_stage_name = "Setup"
        create_stage_name = "Create VM"
        vagrant_box_details_stage_name = "Vagrant Box Details"
        converge_stage_name = "Converge VM"
        run_tests_stage_name = "Run Tests"
        download_stage_name = "Download Artefacts"
        cleanup_stage_name = "Cleanup"
        upload_stage_name = "Upload Coverage"
    } else {
        test_suite_name_slug = "${test_suite_name.replaceAll('#', '').replaceAll('\\s+', '-').toLowerCase()}"
        clone_stage_name = "Clone for ${test_suite_name.capitalize()} Tests"
        setup_stage_name = "Setup for ${test_suite_name.capitalize()} Tests"
        create_stage_name = "Create ${test_suite_name.capitalize()} Tests VM"
        vagrant_box_details_stage_name = "${test_suite_name.capitalize()} Vagrant Box Details"
        converge_stage_name = "Converge ${test_suite_name.capitalize()} Tests VM"
        run_tests_stage_name = "Run ${test_suite_name.capitalize()} Tests"
        download_stage_name = "Download ${test_suite_name.capitalize()} Tests Artefacts"
        cleanup_stage_name = "Cleanup ${test_suite_name.capitalize()} Tests"
        upload_stage_name = "Upload ${test_suite_name.capitalize()} Tests Coverage"
    }

    wrappedNode(jenkins_slave_label, global_timeout, notify_slack_channel) {
        withEnv(environ) {

            if ( macos_build ) {
                // Cleanup old VMs
                cleanupLocalVagrantBoxes(
                    global_timeout: global_timeout
                )
            }

            // Checkout the repo
            stage(clone_stage_name) {
                cleanWs notFailBuild: true
                sh label: 'Clone', script: 'git clone --quiet --local /var/jenkins/salt.git . || true ; git config --unset remote.origin.url || true'
                checkout scm
            }

            // Setup the kitchen required bundle
            stage(setup_stage_name) {
                try {
                    withEnv(["USE_STATIC_REQUIREMENTS=0"]) {
                        sh label: 'Write Salt version', script: '''
                        python setup.py write_salt_version
                        '''
                    }
                } catch (Exception write_salt_version_error) {
                    println "Failed to write the 'salt/_version.py' file: ${write_salt_version_error}"
                }
                try {
                    sh label: 'Set bundle install lock file', script: '''
                    # wait at most 15 minutes for other jobs to finish taking care of bundle installs
                    while find /tmp/lock_bundle -mmin -15 | grep -q /tmp/lock_bundle
                    do
                        echo 'bundle install locked, sleeping 10 seconds'
                        sleep 10
                    done
                    touch /tmp/lock_bundle
                    '''
                    if ( macos_build ) {
                        sh label: 'Bundle Install', script: 'bundle install --with vagrant --without ec2 windows docker'
                    } else {
                        sh label: 'Bundle Install', script: 'bundle install --with ec2 windows --without docker vagrant'
                    }
                } finally {
                    sh label: 'Remove bundle install lock file', script: '''
                    rm -f /tmp/lock_bundle
                    '''
                }
            }

            def Integer createExitCode = 1
            def Integer convergeExitCode = 1
            def Integer installRequirementsExitCode = 1

            createExitCode = runTestsCreateVM(
                create_stage_name,
                macos_build,
                use_spot_instances,
                python_version,
                macos_python_version,
                vagrant_box_details_stage_name,
                distro_version,
                distro_arch,
                distro_name,
                test_suite_name_slug
            )
            if ( createExitCode != 0 ) {
                error("Failed to create VM")
                return createExitCode
            }

            try {
                // Since we reserve for spot instances for a maximum of 6 hours,
                // and we also set the maximum of some of the pipelines to 6 hours,
                // the following timeout get's 15 minutes shaved off so that we
                // have at least that ammount of time to download artifacts
                timeout(time: testrun_timeout * 60 - 15, unit: 'MINUTES') {
                    try {
                        convergeExitCode = runTestsConvergeVM(
                            converge_stage_name,
                            macos_build,
                            python_version,
                            macos_python_version,
                            distro_version,
                            distro_arch,
                            distro_name,
                            test_suite_name_slug
                        )
                        if ( convergeExitCode != 0 ) {
                            currentBuild.result = 'FAILURE'
                            echo "Setting currentBuild.result to ${currentBuild.result}"
                        }
                    } catch(e) {
                        // Retry creation once if converge fails
                        echo "Retrying Create VM and Converge VM"
                        sh label: 'Destroy VM', script: 'bundle exec kitchen destroy $TEST_SUITE-$TEST_PLATFORM'
                        createExitCode = runTestsCreateVM(
                            create_stage_name,
                            macos_build,
                            use_spot_instances,
                            python_version,
                            macos_python_version,
                            vagrant_box_details_stage_name,
                            distro_version,
                            distro_arch,
                            distro_name,
                            test_suite_name_slug
                        )
                        if ( createExitCode != 0 ) {
                            currentBuild.result = 'FAILURE'
                            echo "Setting currentBuild.result to ${currentBuild.result}"
                        } else {
                            convergeExitCode = runTestsConvergeVM(
                                converge_stage_name,
                                macos_build,
                                python_version,
                                macos_python_version,
                                distro_version,
                                distro_arch,
                                distro_name,
                                test_suite_name_slug
                            )
                            if ( convergeExitCode != 0 ) {
                                currentBuild.result = 'FAILURE'
                                echo "Setting currentBuild.result to ${currentBuild.result}"
                            }
                        }
                    }

                    installRequirementsExitCode = runTestsInstallRequirements(
                        "Install Test Requirements",
                        python_version,
                        distro_version,
                        distro_arch,
                        distro_name,
                        test_suite_name_slug
                    )
                    if ( installRequirementsExitCode != 0 ) {
                        error "Failed to install the test requirements"
                    } else {
                        withEnv(["SKIP_INSTALL_REQUIREMENTS=1"]) {
                            def runTestsFullReturnCode = 0
                            if (env.CHANGE_ID) {
                                // On PRs, tests for changed files(including slow), if passed, then fast tests.
                                if ( run_full ) {
                                    runTestsFullReturnCode = runTestsFull(
                                        run_tests_stage_name,
                                        nox_passthrough_opts,
                                        python_version,
                                        distro_version,
                                        distro_arch,
                                        distro_name,
                                        test_suite_name_slug,
                                        inactivity_timeout_minutes,
                                        run_full,
                                        rerun_failed_tests,
                                        "(Slow/Full)",
                                        upload_test_coverage,
                                        upload_split_test_coverage
                                    )
                                    if ( runTestsFullReturnCode != 0 ) {
                                        error("Failed To ${run_tests_stage_name} (Slow/Full)")
                                    }
                                } else {
                                    local_environ = [
                                        "FORCE_FULL=false",
                                    ]
                                    if ( disable_from_filenames == false ) {
                                        local_environ << "NOX_ENABLE_FROM_FILENAMES=1"
                                    }
                                    /*
                                     When running the test suite it chunks, specially when running against
                                     the changed files, some of the test groups might not collect any test
                                     and Jenkins does not help with getting the exit code from scripts.
                                     This is where ``pytest-custom-exit-code` and `--suppress-no-test-exit-code`
                                     comes in.
                                     It allows exiting with a 0 exit code when no tests are collected.
                                    */
                                    withEnv(local_environ) {
                                        runTestsFullReturnCode = runTestsFull(
                                            run_tests_stage_name,
                                            "${nox_passthrough_opts} --run-slow --suppress-no-test-exit-code",
                                            python_version,
                                            distro_version,
                                            distro_arch,
                                            distro_name,
                                            test_suite_name_slug,
                                            inactivity_timeout_minutes,
                                            run_full,
                                            rerun_failed_tests,
                                            "(Slow/Changed)",
                                            upload_test_coverage,
                                            upload_split_test_coverage
                                        )
                                    }

                                    if ( runTestsFullReturnCode != 0 ) {
                                        error("Failed To ${run_tests_stage_name} (Slow/Changed)")
                                    }

                                    runTestsFullReturnCode = runTestsFull(
                                        run_tests_stage_name,
                                        nox_passthrough_opts,
                                        python_version,
                                        distro_version,
                                        distro_arch,
                                        distro_name,
                                        test_suite_name_slug,
                                        inactivity_timeout_minutes,
                                        run_full,
                                        rerun_failed_tests,
                                        "(Fast/Full)",
                                        upload_test_coverage,
                                        upload_split_test_coverage
                                    )

                                    if ( runTestsFullReturnCode != 0 ) {
                                        error("Failed To ${run_tests_stage_name} (Fast/Full)")
                                    }
                                }
                            } else {
                                runTestsFullReturnCode = runTestsFull(
                                    run_tests_stage_name,
                                    nox_passthrough_opts,
                                    python_version,
                                    distro_version,
                                    distro_arch,
                                    distro_name,
                                    test_suite_name_slug,
                                    inactivity_timeout_minutes,
                                    run_full,
                                    rerun_failed_tests,
                                    "(Slow/Full)",
                                    upload_test_coverage,
                                    upload_split_test_coverage
                                )
                                if ( runTestsFullReturnCode != 0 ) {
                                    error("Failed To ${run_tests_stage_name} (Slow/Full)")
                                }
                            }
                        }
                    }
                }
            } catch(org.jenkinsci.plugins.workflow.steps.FlowInterruptedException global_timeout_exception) { // timeout reached
                cause = global_timeout_exception.causes.get(0)
                echo "Timeout Exception Caught. Cause ${cause}: ${global_timeout_exception}"
                if (cause instanceof org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution.ExceededTimeout) {
                    timeout_id = "inactivity-timeout"
                    timeout_message = "No output was seen for ${inactivity_timeout_minutes} minutes. Aborted ${run_tests_stage_name}."
                    addWarningBadge(
                        id: timeout_id,
                        text: timeout_message
                    )
                    createSummary(
                        id: timeout_id,
                        icon: 'warning.png',
                        text: "<b>${timeout_message}</b>"
                    )
                }
                throw global_timeout_exception
            } finally {
                stage(cleanup_stage_name) {
                    sh label: 'Destroy VM', script: 'bundle exec kitchen destroy $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
                }
            }
        }
    }
}
// vim: ft=groovy ts=4 sts=4 et
