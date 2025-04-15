# Serengeti Docker Setup

This document explains how to run Serengeti using Docker Compose.

## Prerequisites

- Docker and Docker Compose installed on your system
- Git (to clone the repository)

## Quick Start

1. Clone the repository:
   ```bash
   git clone https://github.com/ao/Serengeti.git
   cd Serengeti
   ```

2. Build and start the Serengeti cluster:
   ```bash
   docker-compose up -d
   ```

3. Access the dashboards:
   - Primary node: http://localhost:1985/dashboard
   - Secondary node: http://localhost:1986/dashboard

4. To stop the cluster:
   ```bash
   docker-compose down
   ```

## Configuration

The Docker Compose setup includes:

- **serengeti-primary**: The primary Serengeti node
- **serengeti-secondary**: A secondary node that connects to the primary

### Environment Variables

You can customize the behavior of Serengeti nodes using environment variables:

- `JAVA_OPTS`: JVM options (e.g., memory settings)
- `SERENGETI_NODE_ID`: Unique identifier for the node
- `SERENGETI_PRIMARY_NODE`: Address of the primary node (for secondary nodes)

## Monitoring (Optional)

The Docker Compose file includes optional monitoring services using Prometheus and Grafana.

To start the cluster with monitoring:

```bash
docker-compose --profile monitoring up -d
```

Access the monitoring dashboards:
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (username: admin, password: admin)

## Data Persistence

Data is stored in Docker volumes:
- `serengeti-primary-data`: Data for the primary node
- `serengeti-secondary-data`: Data for the secondary node
- `prometheus-data`: Prometheus time-series data
- `grafana-data`: Grafana dashboards and settings

To remove all data and start fresh:

```bash
docker-compose down -v
```

## Scaling

To add more secondary nodes:

```bash
docker-compose up -d --scale serengeti-secondary=3
```

Note: When scaling, you'll need to manually configure port mappings for additional nodes.

## Troubleshooting

### Checking Logs

```bash
# View logs for the primary node
docker-compose logs serengeti-primary

# View logs for the secondary node
docker-compose logs serengeti-secondary

# Follow logs in real-time
docker-compose logs -f
```

### Container Health

```bash
# Check container status
docker-compose ps
```

### Restarting Services

```bash
# Restart a specific service
docker-compose restart serengeti-primary

# Restart all services
docker-compose restart