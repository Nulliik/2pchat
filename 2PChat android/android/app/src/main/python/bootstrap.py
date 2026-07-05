import os

def set_config_dir(path):
    os.environ["P2PCHAT_CONFIG_DIR"] = path
    print(f"Bootstrap: P2PCHAT_CONFIG_DIR set to {path}")
