import conftest


def test_debug_browser_scripts_are_not_collected_as_pytest_tests():
    assert set(conftest.collect_ignore) == {
        "test_login_debug.py",
        "test_login_debug2.py",
        "test_usage_status_filter.py",
        "test_agent.py",
    }
