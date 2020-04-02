def call(Map options) {

    def Boolean run_full = true

    if (env.CHANGE_ID) {
        properties([
            buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '3', daysToKeepStr: '', numToKeepStr: '5')),
            parameters([
                booleanParam(defaultValue: true, description: 'Run full test suite', name: 'runFull')
            ])
        ])
        run_full = params.runFull
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
            ]
        ])
    }

    def env = options.get('env')
    def String distro_name = options.get('distro_name')
    def String distro_version = options.get('distro_version')
    def String python_version = options.get('python_version')
    def String golden_images_branch = options.get('golden_images_branch')
    def String nox_env_name = options.get('nox_env_name')
    def String nox_passthrough_opts = options.get('nox_passthrough_opts')
    def Integer testrun_timeout = options.get('testrun_timeout', 6)
    def Integer inactivity_timeout_minutes = options.get('inactivity_timeout_minutes', 30)
    def Boolean use_spot_instances = options.get('use_spot_instances', false)
    def String rbenv_version = options.get('rbenv_version', '2.6.3')
    def String jenkins_slave_label = options.get('jenkins_slave_label', 'kitchen-slave')
    def String notify_slack_channel = options.get('notify_slack_channel', '')
    def String kitchen_driver_file = options.get('kitchen_driver_file', '/var/jenkins/workspace/driver.yml')
    def String kitchen_verifier_file = options.get('kitchen_verifier_file', '/var/jenkins/workspace/nox-verifier.yml')
    def String kitchen_platforms_file = options.get('kitchen_platforms_file', '/var/jenkins/workspace/nox-platforms.yml')
    def String[] extra_codecov_flags = options.get('extra_codecov_flags', [])
    def String ami_image_id = options.get('ami_image_id', '')
    def Boolean upload_test_coverage = options.get('upload_test_coverage', true)
    def Boolean upload_split_test_coverage = options.get('upload_split_test_coverage', false)
    def Integer concurrent_builds = options.get('concurrent_builds', 1)
    def String test_suite_name = options.get('test_suite_name', 'full')
    def String vm_hostname = computeMachineHostname(
        env: env,
        distro_name: distro_name,
        distro_version: distro_version,
        python_version: python_version,
        nox_env_name: nox_env_name,
        extra_parts: extra_codecov_flags,
    )

    if ( notify_slack_channel == '' ) {
        if (env.CHANGE_ID) {
            // This is a PR
            notify_slack_channel = '#jenkins-prod-pr'
        } else {
            // This is a branch build
            notify_slack_channel = '#jenkins-prod'
        }
    }

    def Boolean macos_build = false
    if ( distro_name == 'macosx' ) {
        macos_build = true
    }

    // Define a global pipeline timeout. This is the test run timeout with one(1) additional
    // hour to allow for artifacts to be downloaded, if possible.
    def global_timeout = testrun_timeout + 1

    // Enforce build concurrency
    enforceBuildConcurrency(options)

    echo """\
    Distro: ${distro_name}
    Distro Version: ${distro_version}
    Python Version: ${python_version}
    Golden Images Branch: ${golden_images_branch}
    Nox Env Name: ${nox_env_name}
    Nox Passthrough Opts: ${nox_passthrough_opts}
    Test run timeout: ${testrun_timeout} Hours
    Global Timeout: ${global_timeout} Hours
    Full Testsuite Run: ${run_full}
    Use SPOT instances: ${use_spot_instances}
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
        "GOLDEN_IMAGES_CI_BRANCH=${golden_images_branch}",
        "CODECOV_FLAGS=${distro_name}${distro_version},${python_version},${nox_env_name.toLowerCase().split('-').join(',')}",
        "RBENV_VERSION=${rbenv_version}",
        "TEST_SUITE=${python_version}",
        "TEST_PLATFORM=${distro_name}-${distro_version}",
        "FORCE_FULL=${run_full}",
        "TEST_VM_HOSTNAME=${vm_hostname}"
    ]

    if ( ami_image_id != '' ) {
        echo """\
        Amazon AMI: ${ami_image_id}
        """.stripIndent()
    }

    if ( ami_image_id != '' ) {
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
        test_suite_name_slug = "${test_suite_name.replaceAll('#', '').replaceAll(' ', '-')}"
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
                checkout scm
            }

            // Setup the kitchen required bundle
            stage(setup_stage_name) {
                try {
                    sh '''
                    # wait at most 15 minutes for other jobs to finish taking care of bundle installs
                    while find /tmp/lock_bundle -mmin -15 | grep -q /tmp/lock_bundle
                    do
                        echo 'bundle install locked, sleeping 10 seconds'
                        sleep 10
                    done
                    touch /tmp/lock_bundle
                    '''
                    if ( macos_build ) {
                        sh 'bundle install --with vagrant --without ec2 windows docker'
                    } else {
                        sh 'bundle install --with ec2 windows --without docker vagrant'
                    }
                } finally {
                    sh '''
                    rm -f /tmp/lock_bundle
                    '''
                }
            }

            def createVM = {
                stage(create_stage_name) {
                    if ( macos_build ) {
                        stage(vagrant_box_details_stage_name) {
                            sh '''
                            bundle exec kitchen diagnose $TEST_SUITE-$TEST_PLATFORM | grep 'box'; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);
                            '''
                        }
                        try {
                            sh """
                            # wait at most 120 minutes for the other job to finish downloading/creating the vagrant box
                            while find /tmp/lock_${distro_version} -mmin -120 | grep -q /tmp/lock_${distro_version}
                            do
                                echo 'vm creation locked, sleeping 120 seconds'
                                sleep 120
                            done
                            touch /tmp/lock_${distro_version}
                            """
                            sh '''
                            bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);
                            '''
                            sh """
                            if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                                mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${test_suite_name_slug}-create.log"
                            fi
                            if [ -s ".kitchen/logs/kitchen.log" ]; then
                                mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-create.log"
                            fi
                            """
                        } finally {
                            sh """
                            rm -f /tmp/lock_${distro_version}
                            """
                        }
                    } else {
                        retry(3) {
                            if ( use_spot_instances ) {
                                sh '''
                                cp -f ~/workspace/spot.yml .kitchen.local.yml
                                t=$(shuf -i 30-150 -n 1); echo "Sleeping $t seconds"; sleep $t
                                bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM || (bundle exec kitchen destroy $TEST_SUITE-$TEST_PLATFORM; rm .kitchen.local.yml; bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM); (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);
                                '''
                            } else {
                                sh '''
                                t=$(shuf -i 30-150 -n 1); echo "Sleeping $t seconds"; sleep $t
                                bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);
                                '''
                            }
                            sh """
                            if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                                mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${test_suite_name_slug}-create.log"
                            fi
                            if [ -s ".kitchen/logs/kitchen.log" ]; then
                                mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-create.log"
                            fi
                            """
                        }
                        try {
                            sh '''
                            bundle exec kitchen diagnose $TEST_SUITE-$TEST_PLATFORM > kitchen-diagnose-info.txt
                            grep 'image_id:' kitchen-diagnose-info.txt
                            grep 'instance_type:' -A5 kitchen-diagnose-info.txt
                            '''
                        } catch (Exception kitchen_diagnose_error) {
                            println "Failed to get the kitchen diagnose information: ${kitchen_diagnose_error}"
                        } finally {
                            sh '''
                            rm -f kitchen-diagnose-info.txt
                            rm -f .kitchen.local.yml
                            '''
                        }
                    }
                }
            }
            createVM.call()

            try {
                // Since we reserve for spot instances for a maximum of 6 hours,
                // and we also set the maximum of some of the pipelines to 6 hours,
                // the following timeout get's 15 minutes shaved off so that we
                // have at least that ammount of time to download artifacts
                timeout(time: testrun_timeout * 60 - 15, unit: 'MINUTES') {
                    def convergeVM = {
                        stage(converge_stage_name) {
                            if ( macos_build ) {
                                sh '''
                                ssh-agent /bin/bash -xc 'ssh-add ~/.vagrant.d/insecure_private_key; bundle exec kitchen converge $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
                                '''
                            } else {
                                sh '''
                                ssh-agent /bin/bash -xc 'ssh-add ~/.ssh/kitchen.pem; bundle exec kitchen converge $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
                                '''
                            }
                            sh """
                            if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                                mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${test_suite_name_slug}-converge.log"
                            fi
                            if [ -s ".kitchen/logs/kitchen.log" ]; then
                                mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-converge.log"
                            fi
                            """
                        }
                    }
                    try {
                        convergeVM.call()
                    } catch(e) {
                        // Retry creation once if converge fails
                        echo "Retrying Create VM and Converge VM"
                        sh 'bundle exec kitchen destroy $TEST_SUITE-$TEST_PLATFORM'
                        createVM.call()
                        convergeVM.call()
                    }

                    try {
                        timeout(activity: true, time: inactivity_timeout_minutes, unit: 'MINUTES') {
                            stage(run_tests_stage_name) {
                                withEnv(["DONT_DOWNLOAD_ARTEFACTS=1"]) {
                                    sh 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
                                }
                            }
                        }
                    } catch(org.jenkinsci.plugins.workflow.steps.FlowInterruptedException inactivity_exception) { // timeout reached
                        def cause = inactivity_exception.causes.get(0)
                        if (cause instanceof org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution.ExceededTimeout) {
                            def timeout_id = "inactivity-timeout"
                            def timeout_message = "No output was seen for ${inactivity_timeout_minutes} minutes. Aborted ${run_tests_stage_name}."
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
                        throw inactivity_exception
                    }
                }
            } finally {
                try {
                    sh """
                    if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                        mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${test_suite_name_slug}-verify.log"
                    fi
                    if [ -s ".kitchen/logs/kitchen.log" ]; then
                        mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-verify.log"
                    fi
                    """

                    // Let's report about known problems found
                    def List<String> conditions_found = []
                    reportKnownProblems(conditions_found, ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${test_suite_name_slug}-verify.log")

                    stage(download_stage_name) {
                        withEnv(["ONLY_DOWNLOAD_ARTEFACTS=1"]){
                            sh 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM || exit 0'
                        }
                        sh """
                        if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                            mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${test_suite_name_slug}-download.log"
                        fi
                        if [ -s ".kitchen/logs/kitchen.log" ]; then
                            mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-download.log"
                        fi
                        """
                    }

                    sh """
                    # Do not error if there are no files to compress
                    xz .kitchen/logs/*-verify.log || true
                    if tail -n 1 artifacts/logs/runtests-* | grep -q 'exit code: 0'
                    then
                        # Do not error if there are no files to compress
                        xz artifacts/logs/runtests-* || true
                    fi
                    """

                    archiveArtifacts(
                        artifacts: "artifacts/*,artifacts/**/*,.kitchen/logs/*-create.log,.kitchen/logs/*-converge.log,.kitchen/logs/*-verify.log*,.kitchen/logs/*-download.log,artifacts/xml-unittests-output/*.xml",
                        allowEmptyArchive: true
                    )
                    junit 'artifacts/xml-unittests-output/*.xml'
                } finally {
                    stage(cleanup_stage_name) {
                        sh 'bundle exec kitchen destroy $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
                    }
                    if ( upload_test_coverage == true ) {
                        stage(upload_stage_name) {
                            if ( run_full ) {
                                def distro_strings = [
                                    distro_name,
                                    distro_version
                                ]
                                def report_strings = (
                                    [python_version] + nox_env_name.split('-') + extra_codecov_flags
                                ).flatten()
                                if ( upload_split_test_coverage ) {
                                    uploadCodeCoverage(
                                        report_path: 'artifacts/coverage/tests.xml',
                                        report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-tests",
                                        report_flags: ([distro_strings.join('')] + report_strings + ['tests']).flatten()
                                    )
                                    uploadCodeCoverage(
                                        report_path: 'artifacts/coverage/salt.xml',
                                        report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-salt",
                                        report_flags: ([distro_strings.join('')] + report_strings + ['salt']).flatten()
                                    )
                                } else {
                                    uploadCodeCoverage(
                                        report_path: 'artifacts/coverage/salt.xml',
                                        report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-salt",
                                        report_flags: ([distro_strings.join('')] + report_strings).flatten()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}
// vim: ft=groovy ts=4 sts=4 et
