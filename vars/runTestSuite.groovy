
def call(Map options) {

    def splits = options.get('splits', null)
    def chunks = options.get('chunks', null)
    def splits_chunks = options.get('splits_chunks', null)
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
    def _splits_chunks = [
        unit: 1,
        functional: 1,
        integration: 1,
        multimaster: 1,
    ]

    // Enforce build concurrency
    enforceBuildConcurrency(options)
    // Now that we have enforced build concurrency, let's disable it when calling runTests
    options['concurrent_builds'] = -1

    // Remove options which are local and should not be passed to runTests
    if ( splits != null ) {
        options.remove("splits")
    }

    if ( chunks == null ) {
        chunks = 1
    } else {
        options.remove("chunks")
    }

    if ( splits_chunks == null ) {
        splits_chunks = _splits_chunks.clone()
    } else {
        options.remove("splits_chunks")
        _splits_chunks.each { item ->
            if ( ! splits_chunks.containsKey(item.key) ) {
                splits_chunks[item.key] = item.value
            }
        }
    }

    def runtests_chunks = [
        failFast: false
    ]
    def runtests_chunks_options = [:]
    def runtests_options

    if ( ! splits ) {
        if ( chunks > 1 ) {
            echo "Defined Test Suite Chunks: ${chunks}"
            chunks_range = 1..chunks
            chunks_range.each { idx ->
                test_suite_name = "Chunk #${idx}"
                runtests_options = options.clone()
                runtests_options['test_suite_name'] = test_suite_name
                runtests_options['nox_passthrough_opts'] = "--test-group-count=${chunks} --test-group=${idx} ${runtests_options['nox_passthrough_opts']}"
                runtests_chunks_options[test_suite_name] = runtests_options
            }
            chunks_range = null
            runtests_chunks_options.each { item ->
                runtests_chunks[item.key] = {
                    runTests(item.value)
                }
            }
            parallel runtests_chunks
        } else {
            echo "No Splits nor Chunks Defined. Running the full top to bottom test suite"
            runTests(options)
        }
    } else {
        echo "Defined Test Suite Splits: ${splits}"
        if ( splits_chunks ) {
            echo "Test Suite Splits Chunks: ${splits_chunks}"
        }
        splits.each { split ->
            test_suite_name = _splits[split]['name']
            if ( splits_chunks ) {
                _chunks = splits_chunks[split]
                if ( _chunks > 1 ) {
                    chunks_range = 1.._chunks
                    chunks_range.each { idx ->
                        _test_suite_name = "${test_suite_name} #${idx}"
                        runtests_options = options.clone()
                        runtests_options['test_suite_name'] = _test_suite_name
                        runtests_options['nox_passthrough_opts'] = "--test-group-count=${_chunks} --test-group=${idx} ${runtests_options['nox_passthrough_opts']} ${_splits[split]['nox_passthrough_opts']}"
                        if ( ! runtests_options.get('extra_codecov_flags', null) ) {
                            runtests_options['extra_codecov_flags'] = []
                        }
                        runtests_options['extra_codecov_flags'] << split
                        runtests_chunks_options[_test_suite_name] = runtests_options
                    }
                    chunks_range = null
                } else {
                    runtests_options = options.clone()
                    runtests_options['test_suite_name'] = test_suite_name
                    runtests_options['nox_passthrough_opts'] = "${runtests_options['nox_passthrough_opts']} ${_splits[split]['nox_passthrough_opts']}"
                    if ( ! runtests_options.get('extra_codecov_flags', null) ) {
                        runtests_options['extra_codecov_flags'] = []
                    }
                    runtests_options['extra_codecov_flags'] << split
                    runtests_chunks_options[test_suite_name] = runtests_options
                }
            } else {
                runtests_options = options.clone()
                runtests_options['test_suite_name'] = test_suite_name
                runtests_options['nox_passthrough_opts'] = "${runtests_options['nox_passthrough_opts']} ${_splits[split]['nox_passthrough_opts']}"
                if ( ! runtests_options.get('extra_codecov_flags', null) ) {
                    runtests_options['extra_codecov_flags'] = []
                }
                runtests_options['extra_codecov_flags'] << split
                runtests_chunks_options[test_suite_name] = runtests_options
            }
            runtests_chunks_options.each { item ->
                if ( item.key != "failFast" ) {
                    runtests_chunks[item.key] = {
                        runTests(item.value)
                    }
                }
            }
        }
        parallel runtests_chunks
    }
}
