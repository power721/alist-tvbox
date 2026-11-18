"""Verify V14 -> V15 provider-usage preservation and frozen-rate estimates."""
import sys

from model_named_budget_smoke_test import main


if __name__ == "__main__":
    sys.argv.insert(1, "--usage-costs")
    main()
