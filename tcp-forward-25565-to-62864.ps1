param(
    [string]$ListenHost = "127.0.0.1",
    [int]$ListenPort = 25565,
    [string]$TargetHost = "127.0.0.1",
    [int]$TargetPort = 62864
)

$ErrorActionPreference = "Stop"

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Parse($ListenHost), $ListenPort)
$listener.Start()
Write-Host "Forwarding $ListenHost`:$ListenPort -> $TargetHost`:$TargetPort"

function Start-Pump {
    param(
        [System.Net.Sockets.NetworkStream]$InputStream,
        [System.Net.Sockets.NetworkStream]$OutputStream
    )

    return [System.Threading.Tasks.Task]::Run([Action]{
        $buffer = New-Object byte[] 8192
        try {
            while (($read = $InputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                $OutputStream.Write($buffer, 0, $read)
                $OutputStream.Flush()
            }
        } catch {
        } finally {
            try { $OutputStream.Close() } catch {}
            try { $InputStream.Close() } catch {}
        }
    })
}

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        $upstream = [System.Net.Sockets.TcpClient]::new()
        $upstream.Connect($TargetHost, $TargetPort)

        $clientStream = $client.GetStream()
        $upstreamStream = $upstream.GetStream()

        $taskA = Start-Pump -InputStream $clientStream -OutputStream $upstreamStream
        $taskB = Start-Pump -InputStream $upstreamStream -OutputStream $clientStream

        [System.Threading.Tasks.Task]::Run([Action]{
            try {
                [System.Threading.Tasks.Task]::WaitAll(@($taskA, $taskB))
            } catch {
            } finally {
                try { $client.Close() } catch {}
                try { $upstream.Close() } catch {}
            }
        }) | Out-Null
    }
} finally {
    $listener.Stop()
}
