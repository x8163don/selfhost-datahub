#!/bin/bash
set -euxo pipefail

if [[ ! ${RELEASE_SKIP_TEST:-} ]] && [[ ! ${RELEASE_SKIP_INSTALL:-} ]]; then
	../../gradlew build  # also runs tests
elif [[ ! ${RELEASE_SKIP_INSTALL:-} ]]; then
	../../gradlew install
fi

MODULE=datahub_airflow_plugin

# Check packaging constraint.
python -c 'import setuptools; where="./src"; assert setuptools.find_packages(where) == setuptools.find_namespace_packages(where), "you seem to be missing or have extra __init__.py files"'
if [[ ${RELEASE_VERSION:-} ]]; then
    # Replace version with RELEASE_VERSION env variable
    sed -i.bak "s/__version__ = \"1\!0.0.0.dev0\"/__version__ = \"$(echo $RELEASE_VERSION|sed s/-/+/)\"/" src/${MODULE}/__init__.py
else
    vim src/${MODULE}/__init__.py
fi

rm -rf build dist || true
python -m build
if [[ ! ${RELEASE_SKIP_UPLOAD:-} ]]; then
    python -m twine upload 'dist/*'
fi
mv src/${MODULE}/__init__.py.bak src/${MODULE}/__init__.py
