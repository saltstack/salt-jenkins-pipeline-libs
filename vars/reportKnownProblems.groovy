
def call(List<String> conditions_found, String filename) {

    def Boolean retry_condition_found = false
    def String ssh_timeout_id = 'ssh-timeout-warning'
    def String check_exit_codes_id = 'check-exit-codes'
    def String memory_error_id = 'memory-error'

    if ( conditions_found.contains(ssh_timeout_id) == false ) {
        // Let's check for SSH Timeouts
        def ssh_timeout_check_rc = sh(
            label: 'check-for-ssh-timeouts',
            returnStatus: true,
            script: """
            grep -q 'Connection timed out - recvfrom' ${filename}
            """
        )

        if ( ssh_timeout_check_rc == 0 ) {
            // The match succeeded
            conditions_found << ssh_timeout_id
            retry_condition_found = true
            def ssh_timeout_message = 'SSH Timeout Detected'
            addWarningBadge(
                id: ssh_timeout_id,
                text: ssh_timeout_message
            )
            createSummary(
                id: ssh_timeout_id,
                icon: 'warning.png',
                text: "<b>${ssh_timeout_message}</b>"
            )
        }

    }

    if ( conditions_found.contains(check_exit_codes_id) == false ) {
        // Let's check for a test suite exit code of 0 and a nox exit code of -9
        def testsuite_exitcode_check_rc = sh(
            label: 'check-exit-codes',
            returnStatus: true,
            script: """
            grep -q 'Test suite execution finalized with exit code: 0' ${filename}
            """
        )
        if ( testsuite_exitcode_check_rc == 0) {
            // Ok, the test suite reported a 0 exit code.
            // How about NOX?
            def nox_exitcode_check_rc = sh(
                label: 'check-exit-codes',
                returnStatus: true,
                script: """
                grep -q 'failed with exit code -9' ${filename}
                """
            )
            if ( nox_exitcode_check_rc == 0 ) {
                retry_condition_found = true
                conditions_found << check_exit_codes_id
                def check_exit_codes_message = 'Test suite reported exit code of 0 but nox failed with exit code -9.'
                addWarningBadge(
                    id: check_exit_codes_id,
                    text: check_exit_codes_message
                )
                createSummary(
                    id: check_exit_codes_id,
                    icon: 'warning.png',
                    text: "<b>${check_exit_codes_message}</b>"
                )
            }
        }
    }

    if ( conditions_found.contains(memory_error_id) == false ) {
        // Let's check for Memory Errors
        def memory_error_grep_strings = [
            'Cannot allocate memory',
            'The paging file is too small for this operation to complete',
            'MemoryError: Unable to allocate internal buffer',
            'MemoryError$'
        ]
        def memory_error_check_rc = sh(
            label: 'memory-error',
            returnStatus: true,
            script: """
            grep -qE '(${memory_error_grep_strings.join('|')})' ${filename}
            """
        )

        if ( memory_error_check_rc == 0 ) {
            // The match succeeded
            conditions_found << memory_error_id
            retry_condition_found = true
            def memory_error_message = 'Memory Error Detected'
            addWarningBadge(
                id: memory_error_id,
                text: memory_error_message
            )
            createSummary(
                id: memory_error_id,
                icon: 'warning.png',
                text: "<b>${memory_error_message}</b>"
            )
        }

    }

    return retry_condition_found
}
// vim: ft=groovy ts=4 sts=4 et
