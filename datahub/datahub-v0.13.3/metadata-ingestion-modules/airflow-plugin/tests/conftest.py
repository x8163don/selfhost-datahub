import pathlib
import site


def pytest_addoption(parser):
    parser.addoption(
        "--update-golden-files",
        action="store_true",
        default=False,
    )


# See https://coverage.readthedocs.io/en/latest/subprocess.html#configuring-python-for-sub-process-measurement
coverage_startup_code = "import coverage; coverage.process_startup()"
site_packages_dir = pathlib.Path(site.getsitepackages()[0])
pth_file_path = site_packages_dir / "datahub_coverage_startup.pth"
pth_file_path.write_text(coverage_startup_code)
