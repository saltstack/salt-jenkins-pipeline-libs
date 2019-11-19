
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
            runTests(runtests_options)
        }
    }
}
