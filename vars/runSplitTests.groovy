
def call(Map options) {

    def splits = options.get('splits', null)
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
        splits.each { split ->
            runtests_options = options.clone()
            runtests_options['test_suite_name'] = _splits[split]['name']
            runtests_options['nox_passthrough_opts'] = "${runtests_options['nox_passthrough_opts']} ${_splits[split]['nox_passthrough_opts']}"
            if ( ! runtests_options.get('extra_codecov_flags', null) ) {
                runtests_options['extra_codecov_flags'] = []
            }
            runtests_options['extra_codecov_flags'] << split
            runTests(runtests_options)
        }
    }
}
