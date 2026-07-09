import socket
import threading
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

_upnp_mapping = None  # Tuple of (control_url, service_type, external_port)


def discover_gateway_control_url():
    ssdp_request = (
        'M-SEARCH * HTTP/1.1\r\n'
        'HOST: 239.255.255.250:1900\r\n'
        'MAN: "ssdp:discover"\r\n'
        'MX: 2\r\n'
        'ST: urn:schemas-upnp-org:device:WANConnectionDevice:1\r\n'
        '\r\n'
    )

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.settimeout(2.5)
    try:
        sock.sendto(ssdp_request.encode("utf-8"), ("239.255.255.250", 1900))
        while True:
            data, _addr = sock.recvfrom(2048)
            headers = {}
            for line in data.decode("utf-8", errors="ignore").split("\r\n"):
                if ":" in line:
                    key, val = line.split(":", 1)
                    headers[key.strip().upper()] = val.strip()
            if "LOCATION" in headers:
                location = headers["LOCATION"]
                print(f"[UPNP] Found gateway SSDP advertisement: {location}")
                control_url, service_type = parse_desc_xml(location)
                if control_url:
                    return control_url, service_type
    except socket.timeout:
        print("[UPNP] SSDP discovery timeout")
    except Exception as e:
        print(f"[UPNP] SSDP discovery error: {e}")
    finally:
        sock.close()

    print("[UPNP] Retrying SSDP discovery with ssdp:all...")
    ssdp_request_all = (
        'M-SEARCH * HTTP/1.1\r\n'
        'HOST: 239.255.255.250:1900\r\n'
        'MAN: "ssdp:discover"\r\n'
        'MX: 2\r\n'
        'ST: ssdp:all\r\n'
        '\r\n'
    )
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.settimeout(2.5)
    try:
        sock.sendto(ssdp_request_all.encode("utf-8"), ("239.255.255.250", 1900))
        while True:
            data, _addr = sock.recvfrom(2048)
            headers = {}
            for line in data.decode("utf-8", errors="ignore").split("\r\n"):
                if ":" in line:
                    key, val = line.split(":", 1)
                    headers[key.strip().upper()] = val.strip()
            if "LOCATION" in headers:
                location = headers["LOCATION"]
                control_url, service_type = parse_desc_xml(location)
                if control_url:
                    return control_url, service_type
    except socket.timeout:
        pass
    except Exception as e:
        print(f"[UPNP] SSDP discovery fallback error: {e}")
    finally:
        sock.close()

    return None, None


def find_element_by_tag_name(parent, tag_name):
    for elem in parent.iter():
        if elem.tag.split("}")[-1] == tag_name:
            return elem
    return None


def parse_desc_xml(location_url):
    try:
        req = urllib.request.Request(location_url)
        with urllib.request.urlopen(req, timeout=3.0) as response:
            xml_data = response.read()

        root = ET.fromstring(xml_data)
        for service in root.iter():
            tag = service.tag.split("}")[-1]
            if tag == "service":
                service_type_elem = find_element_by_tag_name(service, "serviceType")
                control_url_elem = find_element_by_tag_name(service, "controlURL")
                if service_type_elem is not None and control_url_elem is not None:
                    st = service_type_elem.text
                    cu = control_url_elem.text
                    if st and ("WANIPConnection:1" in st or "WANPPPConnection:1" in st):
                        parsed_loc = urllib.parse.urlparse(location_url)
                        base_url = f"{parsed_loc.scheme}://{parsed_loc.netloc}"
                        if cu.startswith("http://") or cu.startswith("https://"):
                            resolved_url = cu
                        elif cu.startswith("/"):
                            resolved_url = f"{base_url}{cu}"
                        else:
                            path = parsed_loc.path.rsplit("/", 1)[0]
                            resolved_url = f"{base_url}{path}/{cu}"
                        print(f"[UPNP] Discovered control URL: {resolved_url} (Service: {st})")
                        return resolved_url, st
    except Exception as e:
        print(f"[UPNP] Error parsing description XML at {location_url}: {e}")
    return None, None


def add_port_mapping(control_url, service_type, external_port, internal_port, internal_client, protocol="TCP", duration=3600, description="2PChat"):
    soap_body = f"""<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:AddPortMapping xmlns:u="{service_type}">
      <NewRemoteHost></NewRemoteHost>
      <NewExternalPort>{external_port}</NewExternalPort>
      <NewProtocol>{protocol}</NewProtocol>
      <NewInternalPort>{internal_port}</NewInternalPort>
      <NewInternalClient>{internal_client}</NewInternalClient>
      <NewEnabled>1</NewEnabled>
      <NewPortMappingDescription>{description}</NewPortMappingDescription>
      <NewLeaseDuration>{duration}</NewLeaseDuration>
    </u:AddPortMapping>
  </s:Body>
</s:Envelope>"""
    headers = {
        "Content-Type": 'text/xml; charset="utf-8"',
        "SOAPACTION": f'"{service_type}#AddPortMapping"',
    }
    req = urllib.request.Request(control_url, data=soap_body.encode("utf-8"), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=4.0) as response:
            response.read()
            return True
    except Exception as e:
        print(f"[UPNP] SOAP AddPortMapping error: {e}")
        return False


def delete_port_mapping(control_url, service_type, external_port, protocol="TCP"):
    soap_body = f"""<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:DeletePortMapping xmlns:u="{service_type}">
      <NewRemoteHost></NewRemoteHost>
      <NewExternalPort>{external_port}</NewExternalPort>
      <NewProtocol>{protocol}</NewProtocol>
    </u:DeletePortMapping>
  </s:Body>
</s:Envelope>"""
    headers = {
        "Content-Type": 'text/xml; charset="utf-8"',
        "SOAPACTION": f'"{service_type}#DeletePortMapping"',
    }
    req = urllib.request.Request(control_url, data=soap_body.encode("utf-8"), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=4.0) as response:
            response.read()
            return True
    except Exception as e:
        print(f"[UPNP] SOAP DeletePortMapping error: {e}")
        return False


def setup_upnp_in_background(port):
    def run():
        global _upnp_mapping
        try:
            print("[UPNP] Discovering UPnP gateway...")
            control_url, service_type = discover_gateway_control_url()
            if control_url:
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                try:
                    sock.connect(("239.255.255.250", 1900))
                    local_ip = sock.getsockname()[0]
                except Exception:
                    local_ip = None
                finally:
                    sock.close()
                if local_ip and not local_ip.startswith("127."):
                    print(f"[UPNP] Mapping external port {port} to internal client {local_ip}:{port}")
                    success = add_port_mapping(control_url, service_type, port, port, local_ip)
                    if success:
                        print(f"[UPNP] Successfully mapped port {port} via UPnP.")
                        _upnp_mapping = (control_url, service_type, port)
                    else:
                        print("[UPNP] Failed to add port mapping.")
                else:
                    print("[UPNP] Could not determine a valid local IP address.")
            else:
                print("[UPNP] UPnP gateway connection device not found.")
        except Exception as e:
            print(f"[UPNP] Background setup failed: {e}")

    t = threading.Thread(target=run, name="UPnPSetupThread", daemon=True)
    t.start()


def stop_upnp():
    global _upnp_mapping
    if _upnp_mapping:
        control_url, service_type, port = _upnp_mapping
        _upnp_mapping = None

        def run():
            try:
                print(f"[UPNP] Deleting port mapping for port {port}...")
                success = delete_port_mapping(control_url, service_type, port)
                if success:
                    print(f"[UPNP] Port mapping for {port} successfully deleted.")
                else:
                    print("[UPNP] Failed to delete port mapping.")
            except Exception as e:
                print(f"[UPNP] Clean-up thread failed: {e}")

        t = threading.Thread(target=run, name="UPnPCleanupThread", daemon=True)
        t.start()

