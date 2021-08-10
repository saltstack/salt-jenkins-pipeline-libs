def call(Map options) {

    def env = options.get('env')
    def String distro_name = options.get('distro_name')
    def String distro_version = options.get('distro_version')
    def String distro_arch = options.get('distro_arch')
    def Integer concurrent_builds = options.get('concurrent_builds', 1)
    def String jenkins_slave_label = options.get('jenkins_slave_label', 'docker-slave')
    def Boolean supports_py2 = options.get('supports_py2', true)
    def Boolean supports_py3 = options.get('supports_py3', true)
    def String nox_passthrough_opts = options.get('nox_passthrough_opts', null)

    if ( nox_passthrough_opts == null ) {
        nox_passthrough_opts = ""
    }
    // Docker
    def String container_name

    def Boolean container_created = false
    def Boolean tests_passed = false
    def Boolean is_pr_build = isPRBuild()
    def String packer_staging_flag = ""

    if ( is_pr_build ) {
        packer_staging_flag = "--staging"
    }
    // For docker containers, we always build a staging container, we promote(rename) it
    // on master branch builds to non-staging
    packer_staging_flag = ""

    // Enforce build concurrency
    enforceBuildConcurrency(options)
    // Now that we have enforced build concurrency, let's disable it for when calling runTests
    options['concurrent_builds'] = -1

    echo """\
    Build Golden Container:
    -  Supports Py2: ${supports_py2}
    -  Supports Py3: ${supports_py3}

    """.stripIndent()

    stage('Build Container') {
        ansiColor('xterm') {
            node(jenkins_slave_label) {
                try {
                    timeout(time: 1, unit: 'HOURS') {
                        timestamps {

                            // Checkout the repo
                            stage('Clone') {
                                checkout scm
                            }
                            withDockerHubCredentials('docker-hub-credentials') {
                                withPackerVersion("1.7.4") {
                                    sh """
                                    pyenv install 3.7.6 || echo "We already have this python."
                                    pyenv local 3.7.6
                                    if [ ! -d venv ]; then
                                        python -m venv venv
                                    fi
                                    . venv/bin/activate
                                    pip install -r os-images/requirements/py3.6/base.txt
                                    inv build-docker ${packer_staging_flag} --distro=${distro_name} --distro-version=${distro_version} --distro-arch=${distro_arch} --salt-pr=${env.CHANGE_ID}
                                    """
                                }
                            }
                            container_name = sh (
                                script: """
                                cat manifest.json|jq -r ".builds[].custom_data.container_name"
                                """,
                                returnStdout: true
                                ).trim()
                            container_created = true
                            container_built_msg = "Built Container ${container_name}"
                            addInfoBadge(
                                id: 'build-container-badge',
                                text: container_built_msg
                            )
                            createSummary(
                                icon: "/images/48x48/attribute.png",
                                text: container_built_msg
                            )
                        }
                    }
                } finally {
                    cleanWs notFailBuild: true
                }
            }
        }
    }

    options['docker_image_name'] = container_name
    options['upload_test_coverage'] = false
    options['kitchen_driver_file'] = '/var/jenkins/workspace/nox-driver-docker.yml'
    options['kitchen_platforms_file'] = '/var/jenkins/workspace/nox-platforms-docker.yml'
    options['nox_passthrough_opts'] = "${nox_passthrough_opts}"

    def Boolean run_tests = true
    if ( container_created == true ) {
        try {
            if ( is_pr_build == false ) {
                message = "${distro_name}-${distro_version}-${distro_arch} Docker Container `${container_name}` is built. Skip tests?"
                try {
                    slack_message = "${message}\nPlease confirm or deny tests execution &lt;${env.BUILD_URL}|here&gt;"
                    slackSend(
                        channel: "#golden-images",
                        color: '#FF8243',
                        message: slack_message)
                } catch (Exception e2) {
                    sh "echo Failed to send the Slack notification: ${e2}"
                }

                try {
                    timeout(time: 15, unit: 'MINUTES') {
                        run_tests = input(
                            id: 'run-tests',
                            message: "\n\n\n${message}\n",
                            parameters: [
                                booleanParam(
                                    defaultValue: true,
                                    description: 'Push the abort button to skip tests.',
                                    name: 'Please confirm you agree with this'
                                )
                            ]
                        )
                    }
                } catch(t_err) { // timeout reached or input false
                    def user = t_err.getCauses()[0].getUser()
                    if( 'SYSTEM' == user.toString() ) { // SYSTEM means timeout.
                        run_tests = true
                    } else {
                        run_tests = false
                        echo "Test suite execution skipped by: [${user}]"
                    }
                }
            }

            if ( run_tests == true ) {
                if ( supports_py2 == true && supports_py3 == true ) {
                    parallel(
                        Py2: {
                            def py2_options = options.clone()
                            py2_options['python_version'] = 'py2'
                            py2_options['test_suite_name'] = 'Py2'
                            runTests(py2_options)
                        },
                        Py3: {
                            def py3_options = options.clone()
                            py3_options['python_version'] = 'py3'
                            py2_options['test_suite_name'] = 'Py3'
                            runTests(py3_options)
                        },
                        failFast: false
                    )
                    tests_passed = true
                } else if ( supports_py2 == true ) {
                    options['python_version'] = 'py2'
                    runTests(options)
                    tests_passed = true
                } else if ( supports_py3 == true ) {
                    options['python_version'] = 'py3'
                    runTests(options)
                    tests_passed = true
                }
            } else {
                tests_passed = true
            }
        } finally {
            if ( is_pr_build == false ) {
                stage('Promote Docker Container') {
                    try {
                        try {
                            message = "${distro_name}-${distro_version}-${distro_arch} Docker Container `${container_name}` is waiting for CI duties promotion."
                            if (tests_passed) {
                                message = "${message}\nTests Passed"
                            } else {
                                message = "${message}\n*Tests Failed. Take extra care before promoting*."
                            }
                            message = "${message}\nPlease confirm or deny promotion &lt;${env.BUILD_URL}|here&gt;"
                            slackSend(
                                channel: "#golden-images",
                                color: '#FF8243',
                                message: message)
                        } catch (Exception e2) {
                            sh "echo Failed to send the Slack notification: ${e2}"
                        }
                        message = "${distro_name}-${distro_version}-${distro_arch} Docker Container `${container_name}` is waiting for CI duties promotion."
                        if (tests_passed) {
                            message = "${message}\nTests Passed."
                        } else {
                            message = "${message}\nTests Failed. Take extra care before promoting."
                        }
                        timeout(time: 4, unit: 'HOURS') {
                            input id: 'promote-container', message: "\n\n\n${message}\n\n\nPromote Docker Container ${container_name}?\n", ok: 'Promote!'
                        }
                        node(jenkins_slave_label) {
                            try {
                                checkout scm
                                withDockerHubCredentials('docker-hub-credentials') {
                                    withPackerVersion("1.7.4") {
                                        sh """
                                        pyenv install 3.7.6 || echo "We already have this python."
                                        pyenv local 3.7.6
                                        if [ ! -d venv ]; then
                                            python -m venv venv
                                        fi
                                        . venv/bin/activate
                                        pip install -r os-images/requirements/py3.6/base.txt
                                        inv promote-container --container=${container_name} --assume-yes
                                        """
                                    }
                                }
                            } finally {
                                cleanWs notFailBuild: true
                            }
                        }
                        container_built_msg = "Docker Container ${container_name} was promoted for CI duties!"
                        addBadge(
                            id: 'promoted-container-badge',
                            icon: "/static/8361d0d6/images/16x16/accept.png",
                            text: container_built_msg
                        )
                        createSummary(
                            icon: "/images/48x48/accept.png",
                            text: container_built_msg
                        )
                        try {
                            slackSend(
                                channel: "#golden-images",
                                color: '#00FF00',
                                message: "${distro_name}-${distro_version}-${distro_arch} Docker Container `${container_name}` was promoted! (&lt;${env.BUILD_URL}|open&gt;)")
                        } catch (Exception e3) {
                            sh "echo Failed to send the Slack notification: ${e3}"
                        }
                    } catch (Exception e4) {
                        println "Docker Container ${container_name} was NOT promoted for CI duties! Reason: ${e4}"
                        addWarningBadge(
                            id: 'promoted-container-badge',
                            text: "Docker Container ${container_name} was NOT promoted for CI duties!"
                        )
                        createSummary(
                            icon: "/images/48x48/warning.png",
                            text: "Docker Container ${container_name} was &lt;b&gt;NOT&lt;/b&gt; promoted for CI duties!"
                        )
                        try {
                            slackSend(
                                channel: "#golden-images",
                                color: '#FF0000',
                                message: "${distro_name}-${distro_version}-${distro_arch} Docker Container `${container_name}` was *NOT* promoted! (&lt;${env.BUILD_URL}|open&gt;)")
                        } catch (Exception e5) {
                            sh "echo Failed to send the Slack notification: ${e5}"
                        }
                    }
                }
            }
        }
    }
}
// vim: ft=groovy ts=4 sts=4 et
