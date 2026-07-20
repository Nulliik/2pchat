import ipaddress
import socket
import threading
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


MAX_DESCRIPTION_SIZE = 1024 * 1024


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, "UPnP redirects are disabled", headers, fp)


def _local_url_address(url, *, expected_address=None):
    """Validate a literal LAN address without introducing a DNS-rebinding window."""
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("UPnP URL must be an absolute HTTP(S) URL")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("UPnP URL must not contain credentials")
    try:
        address = ipaddress.ip_address(parsed.hostname)
    except ValueError as exc:
        raise ValueError("UPnP URL host must be a literal LAN IP address") from exc

    allowed = (
        isinstance(address, ipaddress.IPv4Address)
        and (
            address in ipaddress.ip_network("10.0.0.0/8")
            or address in ipaddress.ip_network("172.16.0.0/12")
            or address in ipaddress.ip_network("192.168.0.0/16")
        )
    ) or (
        isinstance(address, ipaddress.IPv6Address)
        and address in ipaddress.ip_network("fc00::/7")
        and not address.is_loopback
    )
    if not allowed or address.is_multicast or address.is_unspecified or address.is_loopback:
        raise ValueError("UPnP URL must point to a private or link-local LAN address")
    if expected_address is not None:
        expected = ipaddress.ip_address(str(expected_address).split("%", 1)[0])
        if address != expected:
            raise ValueError("UPnP LOCATION does not match its SSDP responder")
    return address


def _open_upnp_url(request, *, timeout):
    opener = urllib.request.build_opener(_NoRedirectHandler())
    return opener.open(request, timeout=timeout)

_upnp_mapping = None  # Tuple of (control_url, service_type, external_port)
_upnp_status_lock = threading.Lock()
_upnp_status = {
    "mapped": False,
    "state": "idle",
    "external_ip": "n/a",
    "local_ip": "n/a",
    "port": "n/a",
    "service_type": "n/a",
    "control_url": "n/a",
    "error": "",
}


def _update_upnp_status(**values):
    with _upnp_status_lock:
        _upnp_status.update(values)


def get_upnp_status():
    """Return a stable diagnostics snapshot without exposing mutable internals."""
    with _upnp_status_lock:
        return dict(_upnp_status)


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
            data, responder = sock.recvfrom(2048)
            headers = {}
            for line in data.decode("utf-8", errors="ignore").split("\r\n"):
                if ":" in line:
                    key, val = line.split(":", 1)
                    headers[key.strip().upper()] = val.strip()
            if "LOCATION" in headers:
                location = headers["LOCATION"]
                print(f"[UPNP] Found gateway SSDP advertisement: {location}")
                control_url, service_type = parse_desc_xml(location, responder_ip=responder[0])
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
            data, responder = sock.recvfrom(2048)
            headers = {}
            for line in data.decode("utf-8", errors="ignore").split("\r\n"):
                if ":" in line:
                    key, val = line.split(":", 1)
                    headers[key.strip().upper()] = val.strip()
            if "LOCATION" in headers:
                location = headers["LOCATION"]
                control_url, service_type = parse_desc_xml(location, responder_ip=responder[0])
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


def parse_desc_xml(location_url, *, responder_ip=None):
    try:
        location_address = _local_url_address(
            location_url,
            expected_address=responder_ip,
        )
        req = urllib.request.Request(location_url)
        with _open_upnp_url(req, timeout=3.0) as response:
            xml_data = response.read(MAX_DESCRIPTION_SIZE + 1)
        if len(xml_data) > MAX_DESCRIPTION_SIZE:
            raise ValueError("UPnP description exceeds the 1 MiB limit")

        root = ET.fromstring(xml_data)
        for service in root.iter():
            tag = service.tag.split("}")[-1]
            if tag == "service":
                service_type_elem = find_element_by_tag_name(service, "serviceType")
                control_url_elem = find_element_by_tag_name(service, "controlURL")
                if service_type_elem is not None and control_url_elem is not None:
                    st = service_type_elem.text
                    cu = control_url_elem.text
                    if st and ("WANIPConnection" in st or "WANPPPConnection" in st):
                        resolved_url = urllib.parse.urljoin(location_url, cu)
                        _local_url_address(resolved_url, expected_address=location_address)
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
        _local_url_address(control_url)
        with _open_upnp_url(req, timeout=4.0) as response:
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
        _local_url_address(control_url)
        with _open_upnp_url(req, timeout=4.0) as response:
            response.read()
            return True
    except Exception as e:
        print(f"[UPNP] SOAP DeletePortMapping error: {e}")
        return False


def setup_upnp_in_background(port):
    def run():
        global _upnp_mapping
        try:
            _update_upnp_status(mapped=False, state="discovering", port=str(port), error="")
            print("[UPNP] Discovering UPnP gateway...")
            control_url, service_type = discover_gateway_control_url()
            if control_url:
                _update_upnp_status(control_url=control_url, service_type=service_type or "n/a")
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                try:
                    sock.connect(("239.255.255.250", 1900))
                    local_ip = sock.getsockname()[0]
                except Exception:
                    local_ip = None
                finally:
                    sock.close()
                if local_ip and not local_ip.startswith("127."):
                    _update_upnp_status(local_ip=local_ip, state="mapping")
                    print(f"[UPNP] Mapping external port {port} to internal client {local_ip}:{port}")
                    success = add_port_mapping(control_url, service_type, port, port, local_ip)
                    if success:
                        print(f"[UPNP] Successfully mapped port {port} via UPnP.")
                        _upnp_mapping = (control_url, service_type, port)
                        _update_upnp_status(mapped=True, state="mapped", error="")
                    else:
                        print("[UPNP] Failed to add port mapping.")
                        _update_upnp_status(mapped=False, state="failed", error="router rejected AddPortMapping")
                else:
                    print("[UPNP] Could not determine a valid local IP address.")
                    _update_upnp_status(mapped=False, state="failed", error="no usable local IPv4 address")
            else:
                print("[UPNP] UPnP gateway connection device not found.")
                _update_upnp_status(mapped=False, state="unavailable", error="UPnP gateway not found")
        except Exception as e:
            print(f"[UPNP] Background setup failed: {e}")
            _update_upnp_status(mapped=False, state="failed", error=str(e))

    t = threading.Thread(target=run, name="UPnPSetupThread", daemon=True)
    t.start()


def stop_upnp():
    global _upnp_mapping
    if _upnp_mapping:
        control_url, service_type, port = _upnp_mapping
        _upnp_mapping = None
        _update_upnp_status(mapped=False, state="stopping")

        def run():
            try:
                print(f"[UPNP] Deleting port mapping for port {port}...")
                success = delete_port_mapping(control_url, service_type, port)
                if success:
                    print(f"[UPNP] Port mapping for {port} successfully deleted.")
                    _update_upnp_status(state="idle", error="")
                else:
                    print("[UPNP] Failed to delete port mapping.")
                    _update_upnp_status(state="failed", error="router rejected DeletePortMapping")
            except Exception as e:
                print(f"[UPNP] Clean-up thread failed: {e}")
                _update_upnp_status(state="failed", error=str(e))

        t = threading.Thread(target=run, name="UPnPCleanupThread", daemon=True)
        t.start()
