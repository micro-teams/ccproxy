package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"

	"github.com/spf13/cobra"

	"github.com/micro-teams/micro-connector/cli/auth"
	"github.com/micro-teams/micro-connector/cli/brand"
	"github.com/micro-teams/micro-connector/cli/config"
	"github.com/micro-teams/micro-connector/cli/screen"
	"github.com/micro-teams/micro-connector/cli/service"
	"github.com/micro-teams/micro-connector/cli/terminal"
	"github.com/micro-teams/micro-connector/cli/transport/ws"
	"github.com/micro-teams/micro-connector/cli/update"
)

func main() {
	// Say who we are before anything reads a path, an env var or an endpoint. A connector is one
	// product for its whole life, so this is set once, here, and never again.
	brand.Current = ccproxyBrand

	if err := root().Execute(); err != nil {
		fmt.Fprintln(os.Stderr, brand.Current.Name+":", err)
		os.Exit(1)
	}
}

func root() *cobra.Command {
	var cfgPath string
	c := &cobra.Command{
		Use:           brand.Current.Name,
		Short:         "Connect this machine to its CCProxy control plane",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	c.PersistentFlags().StringVar(&cfgPath, "config", config.DefaultPath(), "config file path")
	c.AddCommand(
		enrollCmd(&cfgPath),
		runCmd(&cfgPath),
		connectCmd(&cfgPath),
		disconnectCmd(&cfgPath),
		statusCmd(&cfgPath),
		updateCmd(&cfgPath),
		uninstallCmd(&cfgPath),
	)
	return c
}

// enroll performs the device flow against the control plane and writes the machine's durable
// identity to the config. It is idempotent enough to re-run: a machine that is already enrolled is
// simply told so.
func enrollCmd(cfgPath *string) *cobra.Command {
	return &cobra.Command{
		Use:   "enroll [base-url]",
		Short: "Enrol this machine and store its identity",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg := loadOrNew(*cfgPath)
			if len(args) == 1 {
				cfg.Base = strings.TrimRight(args[0], "/")
			}
			if cfg.Base == "" {
				return errors.New("no control-plane base URL: pass one, e.g. `enroll https://host/ccproxy`")
			}
			if cfg.Token != "" {
				fmt.Println("already enrolled as", cfg.MachineID)
				return nil
			}
			return enroll(cmd.Context(), *cfgPath, cfg)
		},
	}
}

// enroll runs the device flow and saves the result. Split out so `connect` can reuse it.
func enroll(ctx context.Context, cfgPath string, cfg *config.Config) error {
	res, err := auth.Login(ctx, cfg.Base, func(url string) {
		fmt.Println("approve this machine at:", url)
	})
	if err != nil {
		return err
	}
	cfg.Token, cfg.MachineID = res.Token, res.MachineID
	if err := config.Save(cfgPath, cfg); err != nil {
		return err
	}
	fmt.Println("enrolled as", cfg.MachineID)
	return nil
}

// run is the resident host: it dials the control plane and serves screens until stopped. The
// service manager launches exactly this (`run --config <path>`); a person can also run it in the
// foreground to watch it.
func runCmd(cfgPath *string) *cobra.Command {
	return &cobra.Command{
		Use:    "run",
		Short:  "Run the resident connector in the foreground (used by the service)",
		Hidden: true,
		RunE: func(cmd *cobra.Command, args []string) error {
			return service.RunForeground(*cfgPath, resident(*cfgPath))
		},
	}
}

// resident is the work the service performs: dial out and pump control-plane messages into the
// screen manager, reconnecting on drops, until the context is cancelled. It deliberately does NOT
// tear down the tmux server on stop — screens must survive a service restart or self-update, and
// the manager re-adopts them when it comes back.
func resident(cfgPath string) service.Runner {
	return func(ctx context.Context) error {
		cfg, err := config.Load(cfgPath)
		if err != nil {
			return err
		}
		if cfg.Token == "" {
			return errors.New("not enrolled: run `enroll` (or `connect`) first")
		}
		ctrlURL, err := cfg.ControlURL()
		if err != nil {
			return err
		}
		tm, err := terminal.NewManager()
		if err != nil {
			return err
		}
		conn := ws.New(ctrlURL, cfg.Token, cfg.APIBase())
		mgr := screen.NewManager(ctx, conn, tm)
		defer mgr.CloseAll()
		return conn.Run(ctx, mgr.Dispatch)
	}
}

// connect enrols the machine if it is not already, then installs and starts the boot service so it
// reconnects on every boot. This is the one command a freshly-installed machine is told to run.
func connectCmd(cfgPath *string) *cobra.Command {
	return &cobra.Command{
		Use:   "connect [base-url]",
		Short: "Enrol (if needed) and stay connected across reboots",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg := loadOrNew(*cfgPath)
			if len(args) == 1 {
				cfg.Base = strings.TrimRight(args[0], "/")
			}
			if cfg.Base == "" {
				return errors.New("no control-plane base URL: pass one, e.g. `connect https://host/ccproxy`")
			}
			if cfg.Token == "" {
				if err := enroll(cmd.Context(), *cfgPath, cfg); err != nil {
					return err
				}
			}
			if err := service.Control(*cfgPath, "install"); err != nil {
				return fmt.Errorf("install service: %w", err)
			}
			if err := service.Control(*cfgPath, "start"); err != nil {
				return fmt.Errorf("start service: %w", err)
			}
			fmt.Println("connected; the connector will reconnect on boot")
			return nil
		},
	}
}

// disconnect stops and removes the boot service, acting on whichever privilege variant is actually
// installed. The machine's identity is left in place, so `connect` brings it straight back.
func disconnectCmd(cfgPath *string) *cobra.Command {
	return &cobra.Command{
		Use:   "disconnect",
		Short: "Stop reconnecting on boot",
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := service.ControlInstalled(*cfgPath, "stop"); err != nil && !errors.Is(err, service.ErrNotInstalled) {
				return err
			}
			if err := service.ControlInstalled(*cfgPath, "uninstall"); err != nil && !errors.Is(err, service.ErrNotInstalled) {
				return err
			}
			fmt.Println("disconnected")
			return nil
		},
	}
}

func statusCmd(cfgPath *string) *cobra.Command {
	return &cobra.Command{
		Use:   "status",
		Short: "Show enrolment and connection status",
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg, err := config.Load(*cfgPath)
			if err != nil {
				fmt.Println("enrolment: not configured")
			} else if cfg.Token == "" {
				fmt.Println("enrolment: not enrolled")
			} else {
				fmt.Printf("enrolment: %s (control plane %s)\n", cfg.MachineID, cfg.APIBase())
			}
			st, _ := service.Status(*cfgPath)
			fmt.Println("service:", st)
			return nil
		},
	}
}

// update replaces this binary with the current one the control plane publishes, then restarts the
// service if one is running so the new binary takes over.
func updateCmd(cfgPath *string) *cobra.Command {
	return &cobra.Command{
		Use:   "update",
		Short: "Update the connector binary from the control plane",
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg, err := config.Load(*cfgPath)
			if err != nil {
				return err
			}
			tmp, err := update.Fetch(cmd.Context(), cfg.APIBase())
			if err != nil {
				return err
			}
			self, err := update.SelfPath()
			if err != nil {
				return err
			}
			if err := update.Replace(tmp, self); err != nil {
				return err
			}
			// Best-effort restart: if a service is running, hand it the new binary. An error here is
			// not fatal — the update landed, and a stopped machine just picks it up next start.
			_ = service.ControlInstalled(*cfgPath, "restart")
			fmt.Println("updated")
			return nil
		},
	}
}

// uninstall removes the service and the machine's on-disk identity. The binary itself is left where
// it is; a package manager or the person who curled it owns that.
func uninstallCmd(cfgPath *string) *cobra.Command {
	return &cobra.Command{
		Use:   "uninstall",
		Short: "Remove the service and this machine's stored identity",
		RunE: func(cmd *cobra.Command, args []string) error {
			_ = service.ControlInstalled(*cfgPath, "stop")
			_ = service.ControlInstalled(*cfgPath, "uninstall")
			if err := os.Remove(*cfgPath); err != nil && !errors.Is(err, os.ErrNotExist) {
				return err
			}
			fmt.Println("uninstalled")
			return nil
		},
	}
}

// loadOrNew returns the existing config, or a blank one when there is none yet — enrolment writes
// the first real copy.
func loadOrNew(path string) *config.Config {
	if cfg, err := config.Load(path); err == nil {
		return cfg
	}
	return &config.Config{}
}
