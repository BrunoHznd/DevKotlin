import requests
import json

# URL da API Zabbix
url = "http://92.113.38.123/zabbix/api_jsonrpc.php"

# Credenciais
username = "" #Inserir Usuario
password = "" #Inserir Senha

# Payload para autenticação
auth_payload = {
    "jsonrpc": "2.0",
    "method": "user.login",
    "params": {
        "username": username,
        "password": password
    },
    "id": 1
}

# Requisição para autenticação
response = requests.post(url, json=auth_payload)

# Exibir a resposta completa para diagnóstico
response_json = response.json()
print("Resposta da autenticação:", json.dumps(response_json, indent=4))

# Verificar se o campo 'result' está na resposta
if 'result' in response_json:
    auth_token = response_json['result']
    print(f"Token de autenticação: {auth_token}")

    # Payload para listar templates (sem filtro)
    template_payload = {
        "jsonrpc": "2.0",
        "method": "template.get",
        "params": {},
        "auth": auth_token,
        "id": 2
    }

    # Requisição para pegar os templates
    response = requests.post(url, json=template_payload)
    templates = response.json().get('result', [])

    # Exibir os templates e seus IDs
    if templates:
        for template in templates:
            print(f"Template: {template['name']}, ID: {template['templateid']}")
    else:
        print("Nenhum template encontrado ou erro na requisição.")
else:
    print("Erro na autenticação. Verifique as credenciais ou a URL do servidor.")
