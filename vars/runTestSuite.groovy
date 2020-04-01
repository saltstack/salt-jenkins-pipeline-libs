
def call(Map options) {

    def splits = options.get('splits', null)
    def parallel_splits = options.get('parallel_splits', false)
    def _splits = [
        unit: [
            name: 'Unit',
            nox_passthrough_opts: 'tests/unit'
        ],
        functional: [
            name: 'Functional',
            nox_passthrough_opts: 'tests/functional'
        ],
        integration: [
            name: 'Integration',
            nox_passthrough_opts: 'tests/integration'
        ],
        multimaster: [
            name: 'Multi-Master',
            nox_passthrough_opts: 'tests/multimaster'
        ]
    ]

    // Enforce build concurrency
    enforceBuildConcurrency(options)
    // Now that we have enforced build concurrency, let's disable it when calling runTests
    options['concurrent_builds'] = -1

    def runtests_options

    if ( ! splits ) {
        echo "No Splits Defined. Running the full top to bottom test suite"
        runTests(options)
    } else {
        options.remove('splits')
        echo "Defined Test Suite Splits: ${splits}"
        echo "Test Suite Parallel Splits: ${parallel_splits}"
        def splits_chunks = [
            failFast: false
        ]
        splits.each { split ->
            test_suite_name = _splits[split]['name']
            splits_chunks[test_suite_name] = {
                runtests_options = options.clone()
                runtests_options['test_suite_name'] = test_suite_name
                runtests_options['nox_passthrough_opts'] = "${runtests_options['nox_passthrough_opts']} ${_splits[split]['nox_passthrough_opts']}"
                if ( ! runtests_options.get('extra_codecov_flags', null) ) {
                    runtests_options['extra_codecov_flags'] = []
                }
                runtests_options['extra_codecov_flags'] << split
                runTests(runtests_options)
            }
        }
        if ( parallel_splits ) {
            parallel splits_chunks
        } else {
            splits_chunks.each { entry ->
                if ( entry.key != "failFast" ) {
                    entry.value.call()
                }
            }
        }
    }
}
