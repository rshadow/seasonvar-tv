import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "container_profile", Path(__file__).resolve().parents[1] / "scripts/profile-container.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ContainerProfileTests(unittest.TestCase):
    def test_physical_device_requires_explicit_opt_in_before_adb(self):
        with patch("sys.argv", ["probe", "--device", "physical", "--output", "unused", "--contention"]), \
                patch.object(MODULE.subprocess, "check_output") as execute, patch("sys.stderr"):
            with self.assertRaises(SystemExit):
                MODULE.main()
            execute.assert_not_called()

    def test_physical_probe_can_only_stop_the_isolated_application(self):
        def device(command, **kwargs):
            arguments = command[4:]
            if arguments == ["get-state"]:
                return "device\n"
            if arguments == ["shell", "pm", "path", "ru.seasonvar.tv.cpucheck"]:
                return "package:/fixture/base.apk\n"
            if arguments == ["shell", "dumpsys", "package", "ru.seasonvar.tv.cpucheck"]:
                return "flags=[ HAS_CODE ]"
            if arguments == ["shell", "dumpsys", "power"]:
                return "mWakefulness=Awake"
            self.assertEqual(["shell", "am", "force-stop", "ru.seasonvar.tv.cpucheck"], arguments)
            raise RuntimeError("isolated stop reached")

        with patch("sys.argv", ["probe", "--device", "physical", "--physical-device",
                               "--output", "unused", "--contention"]), \
                patch.object(MODULE.subprocess, "check_output", side_effect=device):
            with self.assertRaisesRegex(RuntimeError, "isolated stop reached"):
                MODULE.main()
