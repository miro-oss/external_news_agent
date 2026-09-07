"""Safety tests use mocked Docker/Gradle processes and never start a database."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import signal
import subprocess
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location("test_db", Path(__file__).with_name("test-db.py"))
test_db = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(test_db)

RUN_ID = "a" * 32
CONTAINER_ID = "b" * 64
CONTAINER = test_db.CONTAINER_PREFIX + RUN_ID
PASSWORD = "temporary-test-password-never-log"


def environment():
    return {
        "PATH": "/usr/bin:/bin", "DOCKER_HOST": "unix:///var/run/docker.sock",
        "NEWS_TEST_DB_URL": "jdbc:oracle:thin:@127.0.0.1:49152/FREEPDB1",
        "NEWS_TEST_DB_USERNAME": "news_test", "NEWS_TEST_DB_PASSWORD": PASSWORD,
        "NEWS_TEST_DB_CONTAINER": CONTAINER, "NEWS_TEST_DB_RUN_ID": RUN_ID,
    }


def metadata():
    return {"id": CONTAINER_ID, "label": RUN_ID, "user": "news_test", "running": True,
            "health": "healthy", "ports": [{"HostIp": "127.0.0.1", "HostPort": "49152"}]}


class ConfigurationSafetyTests(unittest.TestCase):
    def test_missing_configuration_fails_without_invoking_docker(self):
        with patch.object(test_db, "docker") as docker:
            with self.assertRaises(test_db.TestDbError):
                test_db.validate({})
            docker.assert_not_called()

    def test_operational_or_unrecognized_target_fails_before_inspection(self):
        invalid = {
            "NEWS_TEST_DB_URL": ["jdbc:oracle:thin:@localhost:1521/FREEPDB1",
                                 "jdbc:oracle:thin:@127.0.0.1:1521/FREEPDB1",
                                 "jdbc:oracle:thin:@db.example.com:49152/FREEPDB1",
                                 "jdbc:oracle:thin:@127.0.0.1:49152/OTHER",
                                 "jdbc:oracle:thin:@127.0.0.1:65536/FREEPDB1"],
            "NEWS_TEST_DB_USERNAME": ["NEWS_AGENT", "system"],
            "NEWS_TEST_DB_CONTAINER": ["oracle-free", test_db.CONTAINER_PREFIX + "c" * 32],
            "NEWS_TEST_DB_RUN_ID": ["not-a-run-id"],
        }
        for key, values in invalid.items():
            for value in values:
                with self.subTest(key=key, value=value), patch.object(test_db, "docker") as docker:
                    configured = environment()
                    configured[key] = value
                    with self.assertRaises(test_db.TestDbError):
                        test_db.validate(configured)
                    docker.assert_not_called()

    def test_owned_healthy_container_is_validated_without_writes(self):
        with patch.object(test_db, "docker", return_value=json.dumps(metadata())) as docker:
            test_db.validate(environment())
        self.assertEqual(1, docker.call_count)
        arguments = docker.call_args.args[0]
        self.assertEqual("inspect", arguments[0])
        self.assertIn("--format", arguments)
        self.assertNotIn("{{json .}}", arguments)
        self.assertNotIn("{{json .Config.Env}}", arguments)
        self.assertNotIn(PASSWORD, str(arguments))

    def test_unowned_unhealthy_or_wrong_port_container_is_refused_without_writes(self):
        variants = [
            {"label": "another-run"}, {"id": "oracle-free"}, {"user": "NEWS_AGENT"},
            {"running": False}, {"health": "starting"},
            {"ports": [{"HostIp": "0.0.0.0", "HostPort": "49152"}]},
            {"ports": [{"HostIp": "127.0.0.1", "HostPort": "49153"}]},
            {"ports": [{"HostIp": "127.0.0.1", "HostPort": "1521"}]},
            {"ports": []}, {"ports": None},
        ]
        for variant in variants:
            with self.subTest(variant=variant):
                inspected = metadata() | variant
                with patch.object(test_db, "docker", return_value=json.dumps(inspected)) as docker:
                    with self.assertRaises(test_db.TestDbError):
                        test_db.validate(environment())
                    self.assertTrue(all(call.args[0][0] == "inspect" for call in docker.call_args_list))

    def test_remote_docker_cannot_validate_localhost_database(self):
        configured = environment() | {"DOCKER_HOST": "tcp://remote.example:2376"}
        with patch.object(test_db, "docker") as docker:
            with self.assertRaises(test_db.TestDbError):
                test_db.validate(configured)
            docker.assert_not_called()

    def test_docker_context_takes_precedence_over_docker_host(self):
        configured = environment() | {"DOCKER_CONTEXT": "remote-context"}
        with patch.object(test_db, "docker", return_value='"ssh://remote.example"') as docker:
            with self.assertRaises(test_db.TestDbError):
                test_db.require_local_docker(configured)
            self.assertIn("remote-context", docker.call_args.args[0])

    def test_local_desktop_context_is_supported(self):
        configured = {"DOCKER_CONTEXT": "desktop-linux"}
        with patch.object(test_db, "docker", return_value='"unix:///tmp/docker.sock"'):
            test_db.require_local_docker(configured)


class CleanupSafetyTests(unittest.TestCase):
    def test_removes_only_checked_immutable_id_and_anonymous_volumes(self):
        inspected = metadata() | {"running": False, "health": "unhealthy"}
        with patch.object(test_db, "docker", side_effect=[json.dumps(inspected), ""]) as docker:
            self.assertTrue(test_db.remove_owned_container(CONTAINER, RUN_ID, environment()))
        self.assertEqual(["rm", "--force", "--volumes", CONTAINER_ID], docker.call_args.args[0])

    def test_label_mismatch_never_removes_container(self):
        inspected = metadata() | {"label": "not-owned"}
        with patch.object(test_db, "docker", return_value=json.dumps(inspected)) as docker:
            with self.assertRaises(test_db.TestDbError):
                test_db.remove_owned_container(CONTAINER, RUN_ID, environment())
            self.assertEqual(1, docker.call_count)
            self.assertEqual("inspect", docker.call_args.args[0][0])

    def test_operational_name_is_never_inspected_or_removed(self):
        with patch.object(test_db, "docker") as docker:
            with self.assertRaises(test_db.TestDbError):
                test_db.remove_owned_container("oracle-free", RUN_ID, environment())
            docker.assert_not_called()


class StartupTests(unittest.TestCase):
    def test_waits_for_database_health_before_returning_published_port(self):
        starting = metadata() | {"health": "starting"}
        with patch.object(test_db, "inspect_container", side_effect=[starting, metadata()]), \
                patch.object(test_db.time, "sleep") as sleep:
            self.assertEqual(49152, test_db.wait_until_healthy(CONTAINER, RUN_ID, environment()))
        sleep.assert_called_once_with(2)

    def test_startup_timeout_is_bounded(self):
        with patch.object(test_db.time, "monotonic", side_effect=[0, 0, 301]), \
                patch.object(test_db.time, "sleep"), \
                patch.object(test_db, "inspect_container", return_value=metadata() | {"health": "starting"}):
            with self.assertRaisesRegex(test_db.TestDbError, "Timed out"):
                test_db.wait_until_healthy(CONTAINER, RUN_ID, environment())


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
        self.stack.enter_context(contextlib.redirect_stderr(io.StringIO()))
        self.stack.enter_context(patch.object(test_db.uuid, "uuid4", return_value=Mock(hex=RUN_ID)))
        self.docker = self.stack.enter_context(patch.object(test_db, "docker", return_value=CONTAINER_ID))
        self.wait = self.stack.enter_context(patch.object(test_db, "wait_until_healthy", return_value=49152))
        self.validate = self.stack.enter_context(patch.object(test_db, "validate"))
        self.gradle = self.stack.enter_context(patch.object(test_db, "run_gradle", return_value=0))
        self.cleanup = self.stack.enter_context(patch.object(test_db, "remove_owned_container", return_value=True))

    def test_success_uses_temporary_credentials_and_cleans_up(self):
        source = environment() | {"AGENT_SHARED_SECRET": "inherited-provider-secret",
                                  "SPRING_DATASOURCE_USERNAME": "NEWS_AGENT"}
        self.assertEqual(0, test_db.launch(source, ["*.SeedSourcesMigrationIntegrationTests"]))
        launch_args = self.docker.call_args.args[0]
        docker_env = self.docker.call_args.kwargs["environment"]
        self.assertNotEqual(PASSWORD, docker_env["APP_USER_PASSWORD"])
        self.assertNotIn(docker_env["APP_USER_PASSWORD"], " ".join(launch_args))
        self.assertIn("127.0.0.1::1521", launch_args)
        self.assertNotIn("--volume", launch_args)
        self.assertNotIn("--mount", launch_args)
        self.assertIn("--health-cmd", launch_args)
        self.assertEqual(docker_env["APP_USER_PASSWORD"], self.gradle.call_args.args[0]["NEWS_TEST_DB_PASSWORD"])
        self.assertNotIn("AGENT_SHARED_SECRET", self.gradle.call_args.args[0])
        self.assertNotIn("SPRING_DATASOURCE_USERNAME", self.gradle.call_args.args[0])
        self.cleanup.assert_called_once_with(CONTAINER, RUN_ID, source)

    def test_database_start_failure_still_attempts_owned_cleanup(self):
        self.docker.side_effect = test_db.TestDbError("startup failed")
        with self.assertRaises(test_db.TestDbError):
            test_db.launch(environment(), [])
        self.cleanup.assert_called_once()
        self.gradle.assert_not_called()

    def test_health_timeout_does_not_run_gradle_and_cleans_up(self):
        self.wait.side_effect = test_db.TestDbError("health timeout")
        with self.assertRaises(test_db.TestDbError):
            test_db.launch(environment(), [])
        self.gradle.assert_not_called()
        self.cleanup.assert_called_once()

    def test_test_failure_is_preserved_after_cleanup(self):
        self.gradle.return_value = 1
        self.assertEqual(1, test_db.launch(environment(), []))
        self.cleanup.assert_called_once()

    def test_termination_signal_still_cleans_up(self):
        self.gradle.side_effect = test_db.InterruptedRun(signal.SIGTERM)
        with self.assertRaises(test_db.InterruptedRun):
            test_db.launch(environment(), [])
        self.cleanup.assert_called_once()

    def test_cleanup_failure_cannot_report_success(self):
        self.cleanup.side_effect = test_db.TestDbError("cleanup failed")
        self.assertEqual(2, test_db.launch(environment(), []))


class ProcessBoundaryTests(unittest.TestCase):
    def test_inherited_configuration_and_jvm_injection_are_removed(self):
        poisoned = {key: "poison" for key in [
            "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "GRADLE_OPTS",
            "SPRING_APPLICATION_JSON", "spring.datasource.url", "NEWS_CONFIG_IMPORT",
            "news.agent.enabled", "AGENT_ENABLED", "OPENAI_API_KEY", "NAVER_CLIENT_SECRET",
            "TAVILY_API_KEY", "SERPAPI_API_KEY", "SMTP_PASSWORD", "TELEGRAM_BOT_TOKEN",
            "MINDLOGIC_API_KEY", "ORG_GRADLE_PROJECT_systemProp.spring.datasource.url",
        ]}
        allowed = {"PATH": "/bin", "JAVA_HOME": "/java", "DOCKER_CONTEXT": "desktop-linux",
                   "CLUSTERS_V2_REPLAY_ARTICLES": "/tmp/replay.json"}
        self.assertEqual(allowed, test_db.child_environment(poisoned | allowed))

    def test_gradle_has_fixed_task_and_flags_without_credentials_in_arguments(self):
        process = Mock()
        process.wait.return_value = 0
        with patch.object(test_db.subprocess, "Popen", return_value=process) as popen:
            self.assertEqual(0, test_db.run_gradle(environment(), ["*.SeedSourcesMigrationIntegrationTests"]))
        arguments = popen.call_args.args[0]
        self.assertEqual(["test", "-Dnews.integration.db=true", "--no-daemon", "--rerun-tasks"], arguments[1:5])
        self.assertEqual(["--tests", "*.SeedSourcesMigrationIntegrationTests"], arguments[5:])
        self.assertNotIn(PASSWORD, str(arguments))
        self.assertTrue(popen.call_args.kwargs["start_new_session"])

    def test_gradle_process_group_stops_before_interrupt_propagates(self):
        process = Mock(pid=123)
        process.wait.side_effect = [test_db.InterruptedRun(signal.SIGTERM), 143]
        with patch.object(test_db.subprocess, "Popen", return_value=process), patch.object(test_db.os, "killpg") as killpg:
            with self.assertRaises(test_db.InterruptedRun):
                test_db.run_gradle(environment(), [])
            killpg.assert_called_once_with(123, signal.SIGTERM)

    def test_docker_failure_and_timeout_do_not_expose_output(self):
        outcomes = [subprocess.CompletedProcess([], 1, "", PASSWORD),
                    subprocess.TimeoutExpired(["docker"], 1, output=PASSWORD),
                    FileNotFoundError(PASSWORD)]
        for outcome in outcomes:
            with self.subTest(outcome=type(outcome).__name__):
                options = {"side_effect": outcome} if isinstance(outcome, Exception) else {"return_value": outcome}
                with patch.object(test_db.subprocess, "run", **options):
                    with self.assertRaises(test_db.TestDbError) as caught:
                        test_db.docker(["inspect"])
                    self.assertNotIn(PASSWORD, str(caught.exception))

    def test_arbitrary_gradle_arguments_are_rejected(self):
        for arguments in [["--", "bootRun"], ["-Dnews.integration.db=false"],
                          ["--tests=-Dnews.integration.db=false"], ["--validate", "--tests", "*"]]:
            with self.subTest(arguments=arguments), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as caught:
                    test_db.main(arguments)
                self.assertEqual(2, caught.exception.code)


if __name__ == "__main__":
    unittest.main()
